package lib

import io.circe.Json
import io.circe.generic.auto._
import lib.App._
import lib.serverapi.{LoginResponse, RegistrationResponse, UploadResponse}

sealed trait Command

object Command {
  def apply(name: String, data: Option[Json]): Option[Command] = name match {
    case "ping" => Some(PingCommand)
    case "dirList" => data.flatMap(_.as[DirListCommand].toOption)
    case "saveFileTree" => data.flatMap(_.as[Seq[FileFromTree]].toOption).map(SaveFileTreeCommand)
    case "register" => data.flatMap(_.as[RegisterCommand].toOption)
    case "login" => data.flatMap(_.as[LoginCommand].toOption)
    case "uploadManually" => data.flatMap(_.as[UploadManually].toOption)
    case _ => None
  }
}

case object PingCommand extends Command

case class DirListCommand(path: String) extends Command

case class SaveFileTreeCommand(files: Seq[FileFromTree]) extends Command

case class RegisterCommand(username: String, password: String) extends Command

case class LoginCommand(username: String, password: String) extends Command

case class UploadManually(path: String) extends Command

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

object UploadCommand {
  def toResponse(resp: UploadResponse): Json = {
    resp match {
      case UploadResponse.Uploaded(_) => parseSafe("""{ "success": true }""")
      case UploadResponse.Sha256Mismatch => parseSafe("""{ "success": false, "reason": "SHA-256 mismatch" }""")
    }
  }
}

case class FileFromTree(selected: Boolean, loading: Boolean, value: String, children: Seq[FileFromTree]) {
  def flatten: Seq[FileFromTree] = {
    (this +: children.flatMap(_.flatten)).filterNot(_.loading)
  }

  def toIterable: Iterable[FileFromTree] = new Iterable[FileFromTree] {
    override def iterator: Iterator[FileFromTree] = FileFromTree.this.flatten.iterator
  }

}
