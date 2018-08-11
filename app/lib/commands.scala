package lib

import io.circe.Json
import io.circe.generic.extras.auto._
import lib.App._
import lib.CirceImplicits._
import lib.serverapi.{LoginResponse, RegistrationResponse}

sealed trait Command

object Command {
  def apply(name: String, data: Option[Json]): Option[Command] = name match {
    case "ping" => Some(PingCommand)
    case "dirList" => data.flatMap(_.as[DirListCommand].toOption)
    //    case "saveFileTree" => data.flatMap(_.as[Seq[FileFromTree]].toOption).map(SaveFileTreeCommand)
    case "register" => data.flatMap(_.as[RegisterCommand].toOption)
    case "login" => data.flatMap(_.as[LoginCommand].toOption)
    case "uploadManually" => data.flatMap(_.as[UploadManually].toOption)
    case "download" => data.flatMap(_.as[Download].toOption)
    case _ => None
  }
}

case object PingCommand extends Command

case class DirListCommand(path: String) extends Command

//case class SaveFileTreeCommand(files: Seq[FileFromTree]) extends Command

case class RegisterCommand(username: String, password: String) extends Command

case class LoginCommand(username: String, password: String) extends Command

case class UploadManually(path: String) extends Command

case class Download(path: String, versionId: Long) extends Command

object RegisterCommand {
  def toResponse(resp: RegistrationResponse): Json = {
    resp match {
      case RegistrationResponse.Created(accountId) =>
        parseSafe(s"""{ "success": true, "account_id": "$accountId"}""")
      case RegistrationResponse.AlreadyExists =>
        parseSafe(s"""{ "success": false, "reason": "Account already exists."}""")
    }
  }
}

object LoginCommand {
  def toResponse(resp: LoginResponse): Json = {
    resp match {
      case LoginResponse.SessionCreated(_) => parseSafe("""{ "success": true }""")
      case LoginResponse.SessionRecovered(_) => parseSafe("""{ "success": true }""")
      case LoginResponse.Failed => parseSafe("""{ "success": false }""")
    }
  }
}
