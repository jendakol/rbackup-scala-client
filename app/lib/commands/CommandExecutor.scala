package lib.commands

import better.files.File
import cats.data.EitherT
import cats.syntax.all._
import com.typesafe.scalalogging.StrictLogging
import controllers._
import io.circe.Json
import io.circe.generic.extras.auto._
import io.circe.parser._
import io.circe.syntax._
import io.sentry.Sentry
import io.sentry.event.UserBuilder
import javax.inject.{Inject, Singleton}
import lib.App._
import lib.AppException.LoginRequired
import lib._
import lib.client.clientapi.{BackupSetNode, FileTree, FileTreeNode}
import lib.db.Dao
import lib.server.serverapi._
import lib.server.{CloudConnector, CloudFilesRegistry}
import lib.settings.Settings
import monix.eval.Task
import monix.execution.Scheduler
import org.http4s.Uri
import utils.CirceImplicits._
import utils.ConfigProperty

@Singleton
class CommandExecutor @Inject()(cloudConnector: CloudConnector,
                                filesHandler: FilesHandler,
                                filesRegistry: CloudFilesRegistry,
                                tasksManager: TasksManager,
                                wsApiController: WsApiController,
                                dao: Dao,
                                backupSetsExecutor: BackupSetsExecutor,
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

        tasksManager.start(RunningTask.FileUpload("theName"))(EitherT.right(Task.unit.delayResult(10.seconds))) >>
          cloudConnector.status
            .flatMap { str =>
              parse(s"""{"serverResponse": "$str"}""").toResult
            }
      }

    case StatusCommand =>
      stateManager.status.map { status =>
        parseUnsafe(s"""{ "success": true, "status": "${status.name}", "data": ${status.data}}""")
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
      App.leaveBreadcrumb("Logging in")
      // TODO check the URL
      login(host, username, password)

    case LogoutCommand =>
      App.leaveBreadcrumb("Logging out")
      settings.session(None).mapToJsonSuccess

    case CancelTaskCommand(id) =>
      App.leaveBreadcrumb("Cancelling task", Map("id" -> id))

      tasksManager.cancel(id).map {
        case Some(rt) => parseUnsafe(s"""{ "success": true, "task": ${rt.toJson} }""")
        case None => parseUnsafe("""{ "success": false, "reason": "Task not found" }""")
      }

    case UploadCommand(path) =>
      App.leaveBreadcrumb("Manual upload", Map("path" -> path))

      withSession { implicit session =>
        val file = File(path)

        // TODO check file exists

        uploadManually(file)
      }

    case DownloadCommand(path, versionId) =>
      App.leaveBreadcrumb("Download", Map("path" -> path, "versionId" -> versionId))
      logger.debug(s"Downloading $path with versionId $versionId")

      withSession { implicit session =>
        download(File(path), versionId)
      }

    case BackedUpFileListCommand =>
      App.leaveBreadcrumb("Getting backed-up files list")
      backedUpList

    case BackupSetsListCommand =>
      App.leaveBreadcrumb("Listing backup sets")

      dao.listAllBackupSets().map { bss =>
        val sets = bss.map { bs =>
          val lastTime = bs.lastExecution.map(DateTimeFormatter.format).getOrElse("never")
          val nextTime = bs.lastExecution.map(_.plus(bs.frequency)).map(DateTimeFormatter.format).getOrElse("soon")

          parseUnsafe(
            s"""{ "id": ${bs.id}, "name":"${bs.name}", "processing": ${bs.processing}, "last_execution": "$lastTime", "next_execution": "$nextTime" }""")
        }

        parseUnsafe(s"""{"success": true, "data": [${sets.mkString(",")}]}""")
      }

    case BackupSetDetailsCommand(id) =>
      App.leaveBreadcrumb("Requesting backup set detail", Map("id" -> id))

      dao.listFilesInBackupSet(id).map { files =>
        parseUnsafe(s"""{"success": true, "data": {"files": ${files.map(_.pathAsString).asJson}}}""")
      }

    case BackupSetExecuteCommand(id) =>
      App.leaveBreadcrumb("Requesting backup set execution", Map("id" -> id))

      withSession { implicit session =>
        for {
          bs <- dao.getBackupSet(id)
          _ <- backupSetsExecutor.execute(bs.getOrElse(throw new IllegalArgumentException("Backup set not found"))) // TODO
        } yield {
          JsonSuccess
        }
      }

    case BackupSetFilesUpdateCommand(id, files) =>
      App.leaveBreadcrumb("Updating backup set files", Map("files" -> files))

      for {
        _ <- updateBackupSetFilesList(id, files)
        currentFiles <- dao.listFilesInBackupSet(id)
        _ <- wsApiController.send(
          "backupSetDetailsUpdate",
          parseUnsafe(s"""{ "id": $id, "type": "files", "files":${currentFiles.map(_.pathAsString).asJson}}"""),
          ignoreFailure = true
        )
      } yield JsonSuccess

    case DirListCommand(path) =>
      App.leaveBreadcrumb("Listing dir")
      dirList(path)

    case LoadSettingsCommand =>
      App.leaveBreadcrumb("Loading settings")

      settings.getList.map { map =>
        parseUnsafe(s"""{"success": true, "data": ${map.asJson} }""")
      }

    case SaveSettingsCommand(setts) =>
      App.leaveBreadcrumb("Updating settings", Map("settings" -> setts))

      logger.debug("Updated settings: " + setts)

      settings.saveList(setts).mapToJsonSuccess
  }

  private def dirList(path: String): Result[Json] = {
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
  }

  private def backedUpList: EitherT[Task, AppException, Json] = {
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
        parseUnsafe {
          s"""[{"icon": "fas fa-info-circle", "isLeaf": true, "opened": false, "value": "_", "text": "No backed-up files yet", "isFile": false, "isVersion": false, "isDir": false}]"""
        }
      }
    }
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
            Sentry.getContext.setUser(new UserBuilder().setUsername(username).setId(sessionId.sessionId).build())
            stateManager.login(sessionId).mapToJsonSuccess

          case LoginResponse.SessionRecovered(sessionId) =>
            logger.info("Session on backend restored")
            Sentry.getContext.setUser(new UserBuilder().setUsername(username).setId(sessionId.sessionId).build())
            stateManager.login(sessionId).mapToJsonSuccess

          case LoginResponse.Failed =>
            pureResult(parseUnsafe("""{ "success": false }"""))
        }
      }
  }

  private def processEvent(event: Event): Result[Unit] = event match {
    case InitEvent => pureResult(())

    case PageInitEvent(page) =>
      page match {
        case "status" => tasksManager.notifyUi()
        case _ => pureResult(())
      }
  }

  private def uploadManually(file: File)(implicit ss: ServerSession): Result[Json] = {
    def reportResult(results: List[Option[UploadResponse]]): Result[Unit] = {
      val respJson = if (results.size == 1) {
        results.head match {
          case None => parseUnsafe(s"""{ "success": true, "path": ${file.pathAsString.asJson} }""")
          case Some(UploadResponse.Uploaded(_)) => parseUnsafe(s"""{ "success": true, "path": ${file.pathAsString.asJson} }""")
          case Some(UploadResponse.Sha256Mismatch) =>
            parseUnsafe(s"""{ "success": false, "path": ${file.pathAsString.asJson} "reason": "SHA-256 mismatch" }""")
        }
      } else {
        val failures = results.collect {
          case Some(UploadResponse.Sha256Mismatch) => "Could not upload file" // TODO this is sad
        }

        if (failures.nonEmpty) {
          parseUnsafe(s"""{ "success": false, "path": ${file.pathAsString.asJson}, "reason": "${failures.mkString("[", ", ", "]")}" }""")
        } else {
          parseUnsafe(s"""{ "success": true, "path": ${file.pathAsString.asJson}}""")
        }
      }

      wsApiController.send("finishUpload", respJson, ignoreFailure = true)
    }

    val runningTask = if (file.isDirectory) {
      RunningTask.DirUpload(file.pathAsString)
    } else {
      RunningTask.FileUpload(file.pathAsString)
    }

    tasksManager
      .start(runningTask) {
        for {
          results <- filesHandler.upload(file)
          _ <- filesRegistry.reportBackedUpFilesList
          _ <- reportResult(results)
        } yield ()
      }
      .mapToJsonSuccess
  }

  private def updateBackupSetFilesList(bsId: Long, files: Seq[BackupSetNode]): Result[Unit] = {
    val normalized = files.flatMap(_.flattenNormalize)

    dao.updateFilesInBackupSet(bsId, normalized.map(n => File(n.value)))
  }

  /*
   * .recover {
              case AppException.AccessDenied(_, _) =>
                parseSafe(s"""{ "success": false, "path": ${file.pathAsString.asJson}, "reason": "Access denied" }""")
              case AppException.ServerNotResponding(_) =>
                parseSafe(s"""{ "success": false, "path": ${file.pathAsString.asJson}, "reason": "Server does not respond" }""")
            }
   * */

  private def download(file: File, versionId: Long)(implicit ss: ServerSession): Result[Json] = {
    def reportResult(fileVersion: RemoteFileVersion)(results: List[DownloadResponse]): Result[Unit] = {
      val respJson = if (results.size == 1) {
        results.head match {
          case DownloadResponse.Downloaded(_, _) =>
            parseUnsafe(
              s"""{ "success": true, "path": ${file.pathAsString.asJson}, "time": "${DateTimeFormatter.format(fileVersion.created)}" }""")
          case DownloadResponse.FileVersionNotFound(_) =>
            parseUnsafe(s"""{ "success": false,, "path": ${file.pathAsString.asJson} "reason": "Version not found" }""")
        }
      } else {
        val failures = results.collect {
          case DownloadResponse.FileVersionNotFound(_) => "Version not found" // TODO this is weird
        }

        if (failures.nonEmpty) {
          parseUnsafe(s"""{ "success": false, "path": ${file.pathAsString.asJson}, "reason": "${failures.mkString("[", ", ", "]")}" }""")
        } else {
          parseUnsafe(s"""{ "success": true, "path": ${file.pathAsString.asJson}}""")
        }
      }

      wsApiController.send("finishDownload", respJson, ignoreFailure = true)
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
          wsApiController.send("finishDownload", parseUnsafe {
            s"""{ "success": false, "path": ${file.pathAsString.asJson}, "reason": "Version not found" }"""
          }, ignoreFailure = true)
      }
      .recoverWith {
        case AppException.AccessDenied(_, _) =>
          wsApiController.send(
            `type` = "finishDownload",
            data = parseUnsafe {
              s"""{ "success": false, "path": ${file.pathAsString.asJson}, "reason": "Access to the file was denied" }"""
            },
            ignoreFailure = true
          )
      }

    tasksManager
      .start(RunningTask.FileDownload(file.pathAsString))(downloadTask)
      .mapToJsonSuccess
  }

  private def withSession[A](f: ServerSession => Result[A]): Result[A] = {
    settings.session.flatMap {
      case Some(sid) => f(sid)
      case None => failedResult(LoginRequired())
    }
  }
}
