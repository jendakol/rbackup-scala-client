package lib

import java.time.LocalDateTime

// https://jendakol.github.io/rbackup/server.html
object serverapi {

  case class RemoteFile(id: Long, deviceId: String, originalName: String, versions: List[FileVersion])

  case class FileVersion(version: Long, size: Long, hash: String, created: LocalDateTime)

  // registration
  sealed trait RegistrationResponse

  object RegistrationResponse {

    case class Created(accountId: String) extends RegistrationResponse

    case object AlreadyExists extends RegistrationResponse

  }

  // login
  sealed trait LoginResponse

  object LoginResponse {

    case class SessionCreated(sessionId: String) extends LoginResponse

    case class SessionRecovered(sessionId: String) extends LoginResponse

    case object Failed extends LoginResponse

  }

  // upload
  sealed trait UploadResponse

  object UploadResponse {
    case class Uploaded(file: RemoteFile) extends UploadResponse
    case object Sha256Mismatch extends UploadResponse
  }

}
