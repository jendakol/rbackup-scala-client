package lib

sealed abstract class AppException(msg: String, cause: Throwable = null) extends Exception(msg, cause)

object AppException {

  case object Unauthorized extends AppException("Received request with bad authorization data")

  case class ParsingFailure(input: String, cause: Throwable = null) extends AppException(s"Could not parse: $input", cause)

  case class InvalidArgument(desc: String, cause: Throwable = null) extends AppException(desc, cause)
  case class InvalidResponseException(status: Int, body: String, desc: String, cause: Throwable = null)
      extends AppException(s"Invalid response with status $status: $desc", cause)

}
