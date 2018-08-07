package lib

// https://jendakol.github.io/rbackup/server.html
object serverapi {

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

}
