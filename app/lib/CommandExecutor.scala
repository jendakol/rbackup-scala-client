package lib

import better.files.File
import cats.data.EitherT
import com.typesafe.scalalogging.StrictLogging
import controllers.WsApiController
import io.circe.Json
import io.circe.generic.extras.auto._
import io.circe.parser._
import io.circe.syntax._
import javax.inject.{Inject, Singleton}
import lib.App._
import lib.AppException.LoginRequired
import lib.CirceImplicits._
import lib.clientapi.{ClientStatus, FileTreeNode}
import lib.serverapi.{DownloadResponse, LoginResponse, UploadResponse}
import monix.eval.Task
import monix.execution.Scheduler
import utils.ConfigProperty

@Singleton
class CommandExecutor @Inject()(cloudConnector: CloudConnector,
                                wsApiController: WsApiController,
                                filesRegistry: CloudFilesRegistry,
                                settings: Settings,
                                @ConfigProperty("deviceId") deviceId: String)(implicit scheduler: Scheduler)
    extends StrictLogging {

  def execute(command: Command): Result[Json] = command match {
    case PingCommand =>
      cloudConnector.status
        .flatMap { str =>
          parse(s"""{"serverResponse": "$str"}""").toResult
        }

    case StatusCommand =>
      getStatus.map { status =>
        parseSafe(s"""{ "success": true, "status": "${status.name}"}""")
      }

    case RegisterCommand(username, password) =>
      cloudConnector.registerAccount(username, password).map(RegisterCommand.toResponse)

    case LoginCommand(username, password) =>
      cloudConnector.login(deviceId, username, password).flatMap {
        case LoginResponse.SessionCreated(sessionId) =>
          logger.info("Session on backend created")
          settings.set("sessionId", sessionId).map(_ => parseSafe("""{ "success": true }"""))
        case LoginResponse.SessionRecovered(sessionId) =>
          logger.info("Session on backend restored")
          settings.set("sessionId", sessionId).map(_ => parseSafe("""{ "success": true }"""))
        case LoginResponse.Failed =>
          pureResult(parseSafe("""{ "success": false }"""))
      }

    case LogoutCommand =>
      settings.delete("sessionId").map(_ => parseSafe("""{ "success": true }"""))

    case UploadManually(path) =>
      withSessionId { implicit sessionId =>
        for {
          r <- cloudConnector.upload(File(path))
          _ <- updateFilesRegistry(r)
        } yield {
          r match {
            case UploadResponse.Uploaded(_) => parseSafe("""{ "success": true }""")
            case UploadResponse.Sha256Mismatch => parseSafe("""{ "success": false, "reason": "SHA-256 mismatch" }""")
          }
        }
      }

    case Download(filePath, versionId) =>
      logger.debug(s"Downloading $filePath with versionId $versionId")

      withSessionId { implicit sid =>
        filesRegistry
          .get(File(filePath))
          .flatMap { file =>
            file.versions.find(_.version == versionId).map(file -> _)
          } match {
          case Some((remoteFile, version)) =>
            cloudConnector
              .download(version, File(remoteFile.originalName))
              .map {
                case DownloadResponse.Downloaded(_, _) =>
                  parseSafe(s"""{ "success": true }""")
                case DownloadResponse.FileVersionNotFound(_) =>
                  parseSafe(
                    s"""{ "success": false, "message": "Download of $filePath was not successful\\nVersion not found on server" }""")
              }
              .recover {
                case AppException.AccessDenied(_, _) =>
                  parseSafe(s"""{ "success": false, "message": "Download of $filePath was not successful<br>Access denied" }""")
                case AppException.ServerNotResponding(_) =>
                  parseSafe(s"""{ "success": false, "message": "Server does not respond" }""")
              }

          case None =>
            pureResult(
              parseSafe {
                s"""{ "success": false, "message": "Download of $filePath was not successful<br>Version not found" }"""
              }
            )
        }
      }

    case DirListCommand(path) =>
      val nodes = if (path != "") {
        File(path).children
          .filter(_.isReadable)
          .map { file =>
            if (file.isRegularFile) {
              val versions = filesRegistry.versions(file)
              FileTreeNode.RegularFile(file, versions)
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

  private def getStatus: Result[ClientStatus] = {
    for {
      sessionId <- settings.get("sessionId").map(_.map(SessionId))
      status <- sessionId match {
        case Some(_) =>
          cloudConnector.status
            .map[ClientStatus] { _ =>
              logger.debug("Status READY")
              ClientStatus.Ready
            }
            .recover {
              case e =>
                logger.debug("Server not available - status DISCONNECTED", e)
                ClientStatus.Disconnected
            }

        case None =>
          logger.debug("Session ID not available - status INSTALLED")
          pureResult(ClientStatus.Installed)
      }
    } yield status
  }

  private def withSessionId[A](f: SessionId => Result[A]): Result[A] = {
    settings.sessionId.flatMap {
      case Some(sid) => f(sid)
      case None => failedResult(LoginRequired())
    }
  }

  private def updateFilesRegistry(r: UploadResponse): EitherT[Task, AppException, _ >: CloudFilesList with Unit] = {
    r match {
      case UploadResponse.Uploaded(remoteFile) => filesRegistry.updateFile(remoteFile)
      case _ => pureResult(())
    }
  }
}
