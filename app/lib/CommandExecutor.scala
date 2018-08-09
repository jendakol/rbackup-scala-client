package lib

import better.files.File
import com.typesafe.scalalogging.StrictLogging
import controllers.{WsApiController, WsMessage}
import io.circe.Json
import io.circe.parser._
import javax.inject.{Inject, Singleton}
import lib.App._
import lib.serverapi.UploadResponse
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
      cloudConnector
        .upload(File(path))
        .flatMap { r =>
          // TODO
          wsApiController
            .send(controllers.WsMessage("test", parseSafe("""{ "success": true }""")))
            .map(_ => r)
        }
        .map {
          case UploadResponse.Uploaded(remoteFile) =>
            cloudFilesRegistry.updateFile(remoteFile)

            parseSafe("""{ "success": true }""")
          case UploadResponse.Sha256Mismatch => parseSafe("""{ "success": false, "reason": "SHA-256 mismatch" }""")
        }

    case DirListCommand(path) =>
      val filesList = cloudFilesRegistry.filesList

      val cont = if (path != "") {
        File(path).children
          .filter(_.isReadable)
          .map { file =>
            val n = file.name
            val isFile = file.isRegularFile
            val versions = filesList.versions(file)

            s"""{
               |"text": "${if (n != "") n else "/"}, versions ${versions.toSeq.flatten.map(_.version).mkString("[", ", ", "]")}",
               |"value": "${file.path.toAbsolutePath}",
               |"isLeaf": $isFile,
               |"icon": "fas ${if (isFile) "fa-file" else "fa-folder"} icon-state-default"
               |}
               |""".stripMargin
          }
          .mkString("[", ",", "]")
      } else {

        File.roots
          .filter(_.isReadable)
          .map { f =>
            val n = f.name

            s"""{
               |"text": "${if (n != "") n else "/"}",
               |"value": "${f.path.toAbsolutePath}",
               |"isLeaf": false,
               |"icon": "fas fa-folder icon-state-default"
               |}""".stripMargin
          }
          .mkString("[", ",", "]")
      }

      parse(cont).toResult

    case SaveFileTreeCommand(files) =>
      logger.debug(files.head.flatten.mkString("", "\n", ""))

      pureResult {
        Json.Null
      }

  }
}
