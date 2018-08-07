package lib

import java.io.File

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

    case DirListCommand(path) =>
      val cont = if (path != "") {
        new File(path)
          .listFiles()
          .filter(_.canRead)
          .map { f =>
            val n = f.getName
            val isFile = f.isFile

            s"""{
               |"text": "${if (n != "") n else "/"}",
               |"value": "${f.getAbsolutePath}",
               |"isLeaf": $isFile,
               |"icon": "fas ${if (isFile) "fa-file" else "fa-folder"} icon-state-default"
               |}
               |""".stripMargin
          }
          .mkString("[", ",", "]")
      } else {
        File
          .listRoots()
          .filter(_.canRead)
          .map { f =>
            val n = f.getName

            s"""{
               |"text": "${if (n != "") n else "/"}",
               |"value": "${f.getAbsolutePath}",
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
