package lib

import better.files.File
import com.typesafe.scalalogging.StrictLogging
import io.circe.Json
import io.circe.parser._
import javax.inject.{Inject, Singleton}
import lib.App._
import monix.execution.Scheduler
import utils.ConfigProperty

@Singleton
class CommandExecutor @Inject()(cloudConnector: CloudConnector, @ConfigProperty("deviceId") deviceId: String)(implicit scheduler: Scheduler)
    extends StrictLogging {
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
      cloudConnector.upload(File(path)).map(UploadCommand.toResponse)

    case DirListCommand(path) =>
      val cont = if (path != "") {
        File(path).children
          .filter(_.isReadable)
          .map { f =>
            val n = f.name
            val isFile = f.isRegularFile

            s"""{
               |"text": "${if (n != "") n else "/"}",
               |"value": "${f.path.toAbsolutePath}",
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
