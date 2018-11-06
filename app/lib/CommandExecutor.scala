package lib

import better.files.File
import cats.data.EitherT
import cats.syntax.all._
import com.typesafe.scalalogging.StrictLogging
import controllers.{Event, InitEvent, WsApiController}
import io.circe.Json
import io.circe.generic.extras.auto._
import io.circe.parser._
import io.circe.syntax._
import javax.inject.{Inject, Singleton}
import lib.App._
import lib.AppException.LoginRequired
import lib.CirceImplicits._
import lib.clientapi.{FileTree, FileTreeNode}
import lib.serverapi.{DownloadResponse, LoginResponse, RemoteFileVersion, UploadResponse}
import monix.eval.Task
import monix.execution.Scheduler
import org.http4s.Uri
import utils.ConfigProperty

@Singleton
class CommandExecutor @Inject()(cloudConnector: CloudConnector,
                                filesHandler: FilesHandler,
                                filesRegistry: CloudFilesRegistry,
                                tasksManager: TasksManager,
                                wsApiController: WsApiController,
                                dao: Dao,
                                settings: Settings,
                                stateManager: StateManager,
                                @ConfigProperty("deviceId") deviceId: String)(implicit scheduler: Scheduler)
    extends StrictLogging {

  wsApiController.setEventCallback(processEvent)

  def execute(command: Command): Result[Json] = command match {
    case PingCommand =>
      withSession { implicit session =>
        import cats.syntax.all._

        import scala.concurrent.duration._

        tasksManager.start(RunningTask.FileUpload("theName"), EitherT.right(Task.unit.delayResult(10.seconds))) >>
          cloudConnector.status
            .flatMap { str =>
              parse(s"""{"serverResponse": "$str"}""").toResult
            }
      }

    case StatusCommand =>
      stateManager.status.map { status =>
        parseSafe(s"""{ "success": true, "status": "${status.name}", "data": ${status.data}}""")
      }

    case RegisterCommand(host, username, password) =>
      // TODO check the URL

      for {
        uri <- EitherT.fromEither[Task] {
          Uri.fromString(host).leftMap[AppException](AppException.InvalidArgument("Could not parse provided host", _))
        }
        resp <- cloudConnector.registerAccount(uri, username, password).map(RegisterCommand.toResponse)
      } yield resp

    case LoginCommand(host, username, password) =>
      // TODO check the URL
      login(host, username, password)

    case LogoutCommand =>
      settings.session(None).map(_ => parseSafe("""{ "success": true }"""))

    case CancelTaskCommand(id) =>
      tasksManager.cancel(id).map {
        case Some(rt) => parseSafe(s"""{ "success": true, "task": ${rt.toJson} }""")
        case None => parseSafe("""{ "success": false, "reason": "Task not found" }""")
      }

    case UploadCommand(path) =>
      withSession { implicit session =>
        val file = File(path)

        // TODO check file exists

        uploadManually(file)
      }

    case DownloadCommand(path, versionId) =>
      logger.debug(s"Downloading $path with versionId $versionId")

      withSession { implicit session =>
        download(File(path), versionId)
      }

    case BackedUpFileListCommand =>
      dao.listAllFiles.map { files =>
        val fileTrees = FileTree.fromRemoteFiles(files.map(_.remoteFile))

        logger.debug(s"Backed-up file trees: $fileTrees")

        val nonEmptyTrees = fileTrees.filterNot(_.isEmpty)

        if (nonEmptyTrees.nonEmpty) {
          logger.trace {
            val allFiles = nonEmptyTrees
              .collect {
                case ft @ FileTree(_, Some(_)) => ft.allFiles
                case _ => None
              }
              .flatten
              .flatMap(_.toList)

            s"Returning list of ${allFiles.length} backed-up files"
          }

          nonEmptyTrees.map(_.toJson).asJson
        } else {
          logger.debug("Returning empty list of backed-up files")
          parseSafe {
            s"""[{"icon": "fas fa-info-circle", "isLeaf": true, "opened": false, "value": "_", "text": "No backed-up files yet", "isFile": false, "isVersion": false, "isDir": false}]"""
          }
        }
      }

    case DirListCommand(path) =>
      val nodes = if (path != "") {
        File(path).children
          .filter(_.isReadable)
          .map { file =>
            if (file.isRegularFile) {
              FileTreeNode.RegularFile(file, None)
            } else {
              FileTreeNode.Directory(file)
            }
          }
          .toSeq
      } else {
        File.roots
          .filter(_.isReadable)
          .map(FileTreeNode.Directory(_))
          .toSeq
      }

      pureResult {
        nodes.map(_.toJson).asJson
      }

    //    case SaveFileTreeCommand(files) =>
    ////            logger.debug(better.files.head.flatten.mkString("", "\n", ""))
    //
    //      pureResult {
    //        Json.Null
    //      }

  }

  private def login(host: String, username: String, password: String): Result[Json] = {
    EitherT
      .fromEither[Task] {
        Uri.fromString(host).leftMap[AppException](AppException.InvalidArgument("Could not parse provided host", _))
      }
      .flatMap { uri =>
        cloudConnector.login(uri, deviceId, username, password).flatMap {
          case LoginResponse.SessionCreated(sessionId) =>
            logger.info("Session on backend created")
            stateManager.login(sessionId).map(_ => parseSafe("""{ "success": true }"""))
          case LoginResponse.SessionRecovered(sessionId) =>
            logger.info("Session on backend restored")
            stateManager.login(sessionId).map(_ => parseSafe("""{ "success": true }"""))
          case LoginResponse.Failed =>
            pureResult(parseSafe("""{ "success": false }"""))
        }
      }
  }

  private def processEvent(event: Event): Result[Unit] = event match {
    case InitEvent(page) =>
      page match {
        case "status" => tasksManager.notifyUi()
        case _ => pureResult(())
      }
  }

  private def uploadManually(file: File)(implicit ss: ServerSession): Result[Json] = {
    def reportResult(results: List[UploadResponse]): Result[Unit] = {
      val respJson = if (results.size == 1) {
        results.head match {
          case UploadResponse.Uploaded(_) => parseSafe(s"""{ "success": true, "path": "${file.pathAsString}" }""")
          case UploadResponse.Sha256Mismatch =>
            parseSafe(s"""{ "success": false,, "path": "${file.pathAsString}" "reason": "SHA-256 mismatch" }""")
        }
      } else {
        val failures = results.collect {
          case UploadResponse.Sha256Mismatch => "Could not upload file" // TODO this is sad
        }

        if (failures.nonEmpty) {
          parseSafe(s"""{ "success": false, "path": "${file.pathAsString}", "reason": "${failures.mkString("[", ", ", "]")}" }""")
        } else {
          parseSafe(s"""{ "success": true, "path": "${file.pathAsString}"}""")
        }
      }

      wsApiController.send("finishUpload", respJson)
    }

    val uploadTask: Result[Unit] = for {
      results <- filesHandler.uploadNow(file)
      _ <- filesRegistry.reportBackedUpFilesList
      _ <- reportResult(results)
    } yield ()

    val runningTask = if (file.isDirectory) {
      RunningTask.DirUpload(file.pathAsString)
    } else {
      RunningTask.FileUpload(file.pathAsString)
    }

    tasksManager
      .start(runningTask, uploadTask)
      .map { _ =>
        parseSafe("""{ "success": true }""")
      }
  }

  /*
   * .recover {
              case AppException.AccessDenied(_, _) =>
                parseSafe(s"""{ "success": false, "path": "${file.pathAsString}", "reason": "Access denied" }""")
              case AppException.ServerNotResponding(_) =>
                parseSafe(s"""{ "success": false, "path": "${file.pathAsString}", "reason": "Server does not respond" }""")
            }
   * */

  private def download(file: File, versionId: Long)(implicit ss: ServerSession): Result[Json] = {
    def reportResult(fileVersion: RemoteFileVersion)(results: List[DownloadResponse]): Result[Unit] = {
      val respJson = if (results.size == 1) {
        results.head match {
          case DownloadResponse.Downloaded(_, _) =>
            parseSafe(
              s"""{ "success": true, "path": "${file.pathAsString}", "time": "${DateTimeFormatter.format(fileVersion.created)}" }""")
          case DownloadResponse.FileVersionNotFound(_) =>
            parseSafe(s"""{ "success": false,, "path": "${file.pathAsString}" "reason": "Version not found" }""")
        }
      } else {
        val failures = results.collect {
          case DownloadResponse.FileVersionNotFound(_) => "Version not found" // TODO this is weird
        }

        if (failures.nonEmpty) {
          parseSafe(s"""{ "success": false, "path": "${file.pathAsString}", "reason": "${failures.mkString("[", ", ", "]")}" }""")
        } else {
          parseSafe(s"""{ "success": true, "path": "${file.pathAsString}"}""")
        }
      }

      wsApiController.send("finishDownload", respJson)
    }

    val downloadTask: Result[Unit] = filesRegistry
      .get(file)
      .map(_.flatMap { file =>
        file.versions.collectFirst {
          case fv if fv.version == versionId => file -> fv
        }
      })
      .flatMap {
        case Some((remoteFile, fileVersion)) =>
          filesHandler
            .download(remoteFile, fileVersion, File(remoteFile.originalName))
            .flatMap(reportResult(fileVersion))

        case None =>
          wsApiController.send("finishDownload", parseSafe {
            s"""{ "success": false, "path": "${file.pathAsString}", "reason": "Version not found" }"""
          })
      }

    val runningTask = RunningTask.FileDownload(file.pathAsString)

    tasksManager
      .start(runningTask, downloadTask)
      .map { _ =>
        parseSafe("""{ "success": true }""")
      }
  }

  private def withSession[A](f: ServerSession => Result[A]): Result[A] = {
    settings.session.flatMap {
      case Some(sid) => f(sid)
      case None => failedResult(LoginRequired())
    }
  }
}
