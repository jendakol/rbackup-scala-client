package lib

import better.files.File

sealed abstract class AppException(msg: String, cause: Throwable = null) extends Exception(msg, cause)

object AppException {

  case object Unauthorized extends AppException("Received request with bad authorization data")

  case class ParsingFailure(input: String, cause: Throwable = null) extends AppException(s"Could not parse: $input", cause)

  case class InvalidArgument(desc: String, cause: Throwable = null) extends AppException(desc, cause)

  case class InvalidResponseException(status: Int, body: String, desc: String, cause: Throwable = null)
      extends AppException(s"Invalid response with status $status: $desc", cause)

  case class WsException(desc: String, cause: Throwable = null) extends AppException(desc, cause)

  case class ServerNotResponding(cause: Throwable = null) extends AppException(s"Server not responding", cause)

  case class DbException(desc: String, cause: Throwable = null) extends AppException(s"Error while querying DB ($desc)", cause)

  case class AccessDenied(file: File, cause: Throwable = null) extends AppException(s"Access to '$file' was denied", cause)

  case class LoginRequired(cause: Throwable = null) extends AppException(s"Required login to proceed", cause)

}
