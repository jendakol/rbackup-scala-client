package lib

import java.time.ZonedDateTime

import better.files.File

// https://jendakol.github.io/rbackup/server.html
object serverapi {

  case class RemoteFile(id: Long, deviceId: String, originalName: String, versions: Vector[RemoteFileVersion])

  case class RemoteFileVersion(version: Long, size: Long, hash: Sha256, created: ZonedDateTime)

  // registration
  sealed trait RegistrationResponse

  object RegistrationResponse {

    case class Created(accountId: String) extends RegistrationResponse

    case object AlreadyExists extends RegistrationResponse

  }

  // login
  sealed trait LoginResponse

  object LoginResponse {

    case class SessionCreated(sessionId: SessionId) extends LoginResponse

    case class SessionRecovered(sessionId: SessionId) extends LoginResponse

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

}
