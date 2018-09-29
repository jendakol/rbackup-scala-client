package lib

import better.files.File
import cats.data.EitherT
import com.typesafe.scalalogging.StrictLogging
import io.circe.Json
import io.circe.generic.extras.auto._
import io.circe.parser._
import io.circe.syntax._
import javax.inject.{Inject, Singleton}
import lib.App._
import lib.AppException.LoginRequired
import lib.CirceImplicits._
import lib.clientapi.{FileTree, FileTreeNode}
import lib.serverapi.{DownloadResponse, LoginResponse, UploadResponse}
import monix.eval.Task
import monix.execution.Scheduler
import utils.ConfigProperty

@Singleton
class CommandExecutor @Inject()(cloudConnector: CloudConnector,
                                filesHandler: FilesHandler,
                                filesRegistry: CloudFilesRegistry,
                                dao: Dao,
                                settings: Settings,
                                stateManager: StateManager,
                                @ConfigProperty("deviceId") deviceId: String)(implicit scheduler: Scheduler)
    extends StrictLogging {

  def execute(command: Command): Result[Json] = command match {
    case PingCommand =>
      withSession { implicit session =>
        cloudConnector.status
          .flatMap { str =>
            parse(s"""{"serverResponse": "$str"}""").toResult
          }
      }
    case StatusCommand =>
      stateManager.status.map { status =>
        parseSafe(s"""{ "success": true, "status": "${status.name}"}""")
      }

    case RegisterCommand(host, username, password) =>
      cloudConnector.registerAccount(host, username, password).map(RegisterCommand.toResponse)

    case LoginCommand(host, username, password) =>
      cloudConnector.login(host, deviceId, username, password).flatMap {
        case LoginResponse.SessionCreated(sessionId) =>
          logger.info("Session on backend created")
          stateManager.login(sessionId).map(_ => parseSafe("""{ "success": true }"""))
        case LoginResponse.SessionRecovered(sessionId) =>
          logger.info("Session on backend restored")
          stateManager.login(sessionId).map(_ => parseSafe("""{ "success": true }"""))
        case LoginResponse.Failed =>
          pureResult(parseSafe("""{ "success": false }"""))
      }

    case LogoutCommand =>
      settings.session(None).map(_ => parseSafe("""{ "success": true }"""))

    case UploadManually(path) =>
      withSession { implicit session =>
        val file = File(path)

        for {
          r <- filesHandler.uploadNow(file)
          _ <- updateFilesRegistry(file, r)
        } yield {
          r match {
            case UploadResponse.Uploaded(_) => parseSafe("""{ "success": true }""")
            case UploadResponse.Sha256Mismatch => parseSafe("""{ "success": false, "reason": "SHA-256 mismatch" }""")
          }
        }
      }

    case Download(path, versionId) =>
      logger.debug(s"Downloading $path with versionId $versionId")

      withSession { implicit session =>
        filesRegistry
          .get(File(path))
          .map(_.flatMap { file =>
            file.versions.find(_.version == versionId).map(file -> _)
          })
          .flatMap {
            case Some((remoteFile, version)) =>
              cloudConnector
                .download(version, File(remoteFile.originalName))
                .map {
                  case DownloadResponse.Downloaded(_, _) =>
                    parseSafe(s"""{ "success": true }""")
                  case DownloadResponse.FileVersionNotFound(_) =>
                    parseSafe(s"""{ "success": false, "message": "Download of $path was not successful\\nVersion not found on server" }""")
                }
                .recover {
                  case AppException.AccessDenied(_, _) =>
                    parseSafe(s"""{ "success": false, "message": "Download of $path was not successful<br>Access denied" }""")
                  case AppException.ServerNotResponding(_) =>
                    parseSafe(s"""{ "success": false, "message": "Server does not respond" }""")
                }

            case None =>
              pureResult(
                parseSafe {
                  s"""{ "success": false, "message": "Download of $path was not successful<br>Version not found" }"""
                }
              )
          }
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

  private def withSession[A](f: ServerSession => Result[A]): Result[A] = {
    settings.session.flatMap {
      case Some(sid) => f(sid)
      case None => failedResult(LoginRequired())
    }
  }

  private def updateFilesRegistry(file: File, r: UploadResponse): EitherT[Task, AppException, _ >: CloudFilesList with Unit] = {
    r match {
      case UploadResponse.Uploaded(remoteFile) => filesRegistry.updateFile(file, remoteFile)
      case _ => pureResult(())
    }
  }
}
