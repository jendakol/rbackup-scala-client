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
import lib.serverapi.UploadResponse
import monix.eval.Task
import monix.execution.Scheduler
import utils.ConfigProperty

@Singleton
class CommandExecutor @Inject()(cloudConnector: CloudConnector,
                                wsApiController: WsApiController,
                                cloudFilesRegistry: CloudFilesRegistry,
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

    case DirListCommand(path) =>
      val filesList = cloudFilesRegistry.filesList

      val nodes = if (path != "") {
        File(path).children
          .filter(_.isReadable)
          .map { file =>
            if (file.isRegularFile) {
              val versions = filesList.versions(file)
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
      case UploadResponse.Uploaded(remoteFile) => cloudFilesRegistry.updateFile(remoteFile)
      case _ => pureResult(())
    }
  }
}
