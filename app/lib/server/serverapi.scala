package lib.server

import java.time.ZonedDateTime

import better.files.File
import cats.data.NonEmptyList
import lib.{DeviceId, ServerSession}
import utils.Sha256

// https://jendakol.github.io/rbackup/server.html
object serverapi {

  case class RemoteFile(id: Long, deviceId: String, originalName: String, versions: List[RemoteFileVersion])

  case class RemoteFileVersion(version: Long, size: Long, hash: Sha256, created: ZonedDateTime, mtime: ZonedDateTime)

  // registration
  sealed trait RegistrationResponse

  object RegistrationResponse {

    case class Created(accountId: String) extends RegistrationResponse

    case object AlreadyExists extends RegistrationResponse

  }

  // login
  sealed trait LoginResponse

  object LoginResponse {

    case class SessionCreated(session: ServerSession) extends LoginResponse

    case class SessionRecovered(session: ServerSession) extends LoginResponse

    case object Failed extends LoginResponse

  }

  // upload
  sealed trait UploadResponse

  object UploadResponse {

    case class Uploaded(file: RemoteFile) extends UploadResponse

    case object Sha256Mismatch extends UploadResponse

  }

  // file list
  sealed trait ListFilesResponse

  object ListFilesResponse {

    case class FilesList(files: Seq[RemoteFile]) extends ListFilesResponse

    case class DeviceNotFound(deviceId: DeviceId) extends ListFilesResponse

  }

  // download
  sealed trait DownloadResponse

  object DownloadResponse {

    case class Downloaded(file: File, fileVersion: RemoteFileVersion) extends DownloadResponse

    case class FileVersionNotFound(fileVersion: RemoteFileVersion) extends DownloadResponse

  }

  // remove file
  sealed trait RemoveFileResponse

  object RemoveFileResponse {

    case object Success extends RemoveFileResponse

    case class PartialFailure(failures: NonEmptyList[String]) extends RemoveFileResponse

    case object FileNotFound extends RemoveFileResponse

  }

  // remove file version
  sealed trait RemoveFileVersionResponse

  object RemoveFileVersionResponse {

    case object Success extends RemoveFileVersionResponse

    case object FileNotFound extends RemoveFileVersionResponse

  }

}
