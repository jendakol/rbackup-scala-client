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
import lib.client.clientapi.FileTreeNode
import lib.db.Dao
import lib.server.CloudConnector
import lib.server.serverapi._
import lib.settings.Settings
import monix.eval.Task
import monix.execution.Scheduler
import org.http4s.Uri
import utils.CirceImplicits._

@Singleton
class CommandExecutor @Inject()(cloudConnector: CloudConnector,
                                tasksManager: TasksManager,
                                wsApiController: WsApiController,
                                dao: Dao,
                                backupCommandExecutor: BackupCommandExecutor,
                                fileCommandExecutor: FileCommandExecutor,
                                settings: Settings,
                                stateManager: StateManager,
                                deviceId: DeviceId)(implicit scheduler: Scheduler)
    extends StrictLogging {

  wsApiController.setEventCallback(processEvent)

  def execute(command: Command): Result[Json] = command match {
    case c: BackupCommand => backupCommandExecutor.execute(c)

    case c: FileCommand => fileCommandExecutor.execute(c)

    case PingCommand =>
      withSession { implicit session =>
        import cats.syntax.all._

        import scala.concurrent.duration._

        tasksManager.start(RunningTask.FileUpload(deviceId.value))(EitherT.right(Task.unit.delayResult(10.seconds))) >>
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

  private def withSession[A](f: ServerSession => Result[A]): Result[A] = {
    settings.session.flatMap {
      case Some(sid) => f(sid)
      case None => failedResult(LoginRequired())
    }
  }
}
