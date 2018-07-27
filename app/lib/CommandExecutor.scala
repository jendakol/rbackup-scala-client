package lib

import java.io.File
import java.util.concurrent.TimeUnit

import com.typesafe.scalalogging.StrictLogging
import io.circe.Json
import io.circe.syntax._
import javax.inject.{Inject, Singleton}
import monix.execution.Scheduler

import scala.util.Try

@Singleton
class CommandExecutor @Inject()(scheduler: Scheduler) extends StrictLogging {
  def execute(command: Command, send: Json => Try[Unit]): Unit = command match {
    case PingCommand =>
      scheduler.scheduleAtFixedRate(0, 1, TimeUnit.SECONDS, () => {
        send("{}".asJson).failed.foreach(logger.warn("Error while sending message", _))
      })

    case DirListCommand(path) =>
      send {
        if (path != "") {
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
            .asJson
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
            .asJson
        }
      }

    case SaveFileTreeCommand(files) =>
      logger.debug(files.head.flatten.mkString("", "\n", ""))
      send(Json.Null)

  }
}
