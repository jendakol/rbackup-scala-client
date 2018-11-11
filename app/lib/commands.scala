package lib

import java.util.UUID

import io.circe.Json
import io.circe.generic.extras.auto._
import lib.App._
import lib.CirceImplicits._
import lib.serverapi.{LoginResponse, RegistrationResponse}

sealed trait Command

object Command {
  def apply(name: String, data: Option[Json]): Option[Command] = name match {
    case "ping" => Some(PingCommand)
    case "status" => Some(StatusCommand)
    case "backedUpFileList" => Some(BackedUpFileListCommand)
    case "dirList" => data.flatMap(_.as[DirListCommand].toOption)
    //    case "saveFileTree" => data.flatMap(_.as[Seq[FileFromTree]].toOption).map(SaveFileTreeCommand)
    case "register" => data.flatMap(_.as[RegisterCommand].toOption)
    case "login" => data.flatMap(_.as[LoginCommand].toOption)
    case "logout" => Some(LogoutCommand)
    case "upload" => data.flatMap(_.as[UploadCommand].toOption)
    case "download" => data.flatMap(_.as[DownloadCommand].toOption)
    case "cancelTask" => data.flatMap(_.as[CancelTaskCommand].toOption)
    case _ => None
  }
}

case object PingCommand extends Command

case object StatusCommand extends Command

case class DirListCommand(path: String) extends Command

case object BackedUpFileListCommand extends Command

//case class SaveFileTreeCommand(files: Seq[FileFromTree]) extends Command

case class RegisterCommand(host: String, username: String, password: String) extends Command

case class LoginCommand(host: String, username: String, password: String) extends Command

case object LogoutCommand extends Command

case class UploadCommand(path: String) extends Command

case class DownloadCommand(path: String, versionId: Long) extends Command

case class CancelTaskCommand(id: UUID) extends Command

object RegisterCommand {
  def toResponse(resp: RegistrationResponse): Json = {
    resp match {
      case RegistrationResponse.Created(accountId) =>
        parseUnsafe(s"""{ "success": true, "account_id": "$accountId"}""")
      case RegistrationResponse.AlreadyExists =>
        parseUnsafe(s"""{ "success": false, "reason": "Account already exists."}""")
    }
  }
}

object LoginCommand {
  def toResponse(resp: LoginResponse): Json = {
    resp match {
      case LoginResponse.SessionCreated(_) => parseUnsafe("""{ "success": true }""")
      case LoginResponse.SessionRecovered(_) => parseUnsafe("""{ "success": true }""")
      case LoginResponse.Failed => parseUnsafe("""{ "success": false }""")
    }
  }
}
