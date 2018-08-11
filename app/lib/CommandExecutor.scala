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
import lib.CirceImplicits._
import lib.clientapi.FileTreeNode
import lib.serverapi.{DownloadResponse, UploadResponse}
import monix.eval.Task
import monix.execution.Scheduler
import utils.ConfigProperty

@Singleton
class CommandExecutor @Inject()(cloudConnector: CloudConnector,
                                wsApiController: WsApiController,
                                filesRegistry: CloudFilesRegistry,
                                @ConfigProperty("deviceId") deviceId: String)(implicit scheduler: Scheduler)
    extends StrictLogging {

  implicit val sessionId: SessionId = SessionId("db011aa8-634a-48aa-8370-0ad29a0be517") // TODO

  def execute(command: Command): Result[Json] = command match {
    case PingCommand =>
      cloudConnector.status
        .flatMap { str =>
          parse(s"""{"serverResponse": "$str"}""").toResult
        }

    case RegisterCommand(username, password) =>
      cloudConnector.registerAccount(username, password).map(RegisterCommand.toResponse)

    case LoginCommand(username, password) =>
      cloudConnector.login(deviceId, username, password).map(LoginCommand.toResponse)

    case UploadManually(path) =>
      for {
        r <- cloudConnector.upload(File(path))
        _ <- updateFilesRegistry(r)
      } yield {
        r match {
          case UploadResponse.Uploaded(_) => parseSafe("""{ "success": true }""")
          case UploadResponse.Sha256Mismatch => parseSafe("""{ "success": false, "reason": "SHA-256 mismatch" }""")
        }
      }

    case Download(filePath, versionId) =>
      logger.debug(s"Downloading $filePath with versionId $versionId")

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
                parseSafe(s"""{ "success": false, "message": "Download of $filePath was not successful\\nVersion not found on server" }""")
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

  private def updateFilesRegistry(r: UploadResponse): EitherT[Task, AppException, _ >: CloudFilesList with Unit] = {
    r match {
      case UploadResponse.Uploaded(remoteFile) => filesRegistry.updateFile(remoteFile)
      case _ => pureResult(())
    }
  }
}
