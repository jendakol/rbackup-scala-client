package lib.commands

import java.util.UUID

import io.circe.Json
import io.circe.generic.auto._
import lib.App._
import lib.client.clientapi.BackupSetNode
import lib.server.serverapi.{LoginResponse, RegistrationResponse}

sealed trait Command

object Command {
  def apply(name: String, data: Option[Json]): Option[Command] = name match {
    case "backupSetFiles" => data.flatMap(_.as[BackupSetFilesUpdateCommand].toOption)
    case "backupSetDetails" => data.flatMap(_.as[BackupSetDetailsCommand].toOption)
    case "backupSetExecute" => data.flatMap(_.as[BackupSetExecuteCommand].toOption)
    case "backupSetsList" => Some(BackupSetsListCommand)
    case "backupSetNew" => data.flatMap(_.as[BackupSetNewCommand].toOption)
    case "backupSetDelete" => data.flatMap(_.as[BackupSetDeleteCommand].toOption)
    case "ping" => Some(PingCommand)
    case "status" => Some(StatusCommand)
    case "backedUpFileList" => Some(BackedUpFileListCommand)
    case "dirList" => data.flatMap(_.as[DirListCommand].toOption)
    case "register" => data.flatMap(_.as[RegisterCommand].toOption)
    case "login" => data.flatMap(_.as[LoginCommand].toOption)
    case "logout" => Some(LogoutCommand)
    case "upload" => data.flatMap(_.as[UploadCommand].toOption)
    case "download" => data.flatMap(_.as[DownloadCommand].toOption)
    case "cancelTask" => data.flatMap(_.as[CancelTaskCommand].toOption)
    case "settingsLoad" => Some(LoadSettingsCommand)
    case "settingsSave" => data.flatMap(_.as[SaveSettingsCommand].toOption)
    case _ => None
  }
}

sealed trait BackupCommand extends Command

case object BackedUpFileListCommand extends BackupCommand

case class BackupSetFilesUpdateCommand(id: Long, files: Seq[BackupSetNode]) extends BackupCommand

case class BackupSetDetailsCommand(id: Long) extends BackupCommand

case class BackupSetExecuteCommand(id: Long) extends BackupCommand

case object BackupSetsListCommand extends BackupCommand

case class BackupSetNewCommand(name: String) extends BackupCommand

case class BackupSetDeleteCommand(id: Long) extends BackupCommand

/* -- */

case object PingCommand extends Command

case object StatusCommand extends Command

case class DirListCommand(path: String) extends Command

case class RegisterCommand(host: String, username: String, password: String) extends Command

case class LoginCommand(host: String, username: String, password: String) extends Command

case object LogoutCommand extends Command

case class UploadCommand(path: String) extends Command

case class DownloadCommand(path: String, versionId: Long) extends Command

case class CancelTaskCommand(id: UUID) extends Command

case object LoadSettingsCommand extends Command

case class SaveSettingsCommand(settings: Map[String, String]) extends Command

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
