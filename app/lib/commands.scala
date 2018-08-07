package lib

import io.circe.Json
import io.circe.generic.auto._
import lib.App._
import lib.serverapi.{LoginResponse, RegistrationResponse}

sealed trait Command

object Command {
  def apply(name: String, data: Option[Json]): Option[Command] = name match {
    case "ping" => Some(PingCommand)
    case "dirList" => data.flatMap(_.hcursor.get[String]("path").toOption).map(DirListCommand)
    case "saveFileTree" => data.flatMap(_.as[Seq[FileFromTree]].toOption).map(SaveFileTreeCommand)
    case "register" => data.flatMap(_.as[RegisterCommand].toOption)
    case "login" => data.flatMap(_.as[LoginCommand].toOption)
    case _ => None
  }
}

case object PingCommand extends Command

case class DirListCommand(path: String) extends Command

case class SaveFileTreeCommand(files: Seq[FileFromTree]) extends Command

case class RegisterCommand(username: String, password: String) extends Command

case class LoginCommand(username: String, password: String) extends Command

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

case class FileFromTree(selected: Boolean, loading: Boolean, value: String, children: Seq[FileFromTree]) {
  def flatten: Seq[FileFromTree] = {
    (this +: children.flatMap(_.flatten)).filterNot(_.loading)
  }

  def toIterable: Iterable[FileFromTree] = new Iterable[FileFromTree] {
    override def iterator: Iterator[FileFromTree] = FileFromTree.this.flatten.iterator
  }

}
