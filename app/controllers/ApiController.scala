package controllers

import cats.syntax.either._
import io.circe.Json
import io.circe.generic.auto._
import io.circe.parser.decode
import lib.Command

trait ApiController {

  protected def decodeCommand(json: String): Either[ErrorResponse, Command] = {
    decode[JsonCommand](json)
      .leftMap(e => ErrorResponse(s"${e.getClass.getName}: ${e.getMessage}"))
      .flatMap(decodeCommand)
  }

  protected def decodeCommand(jsonCommand: JsonCommand): Either[ErrorResponse, Command] = {
    import jsonCommand._

    Command(name, data) match {
      case Some(command) => Right(command)
      case None => Left(ErrorResponse("Command not found or is missing some data"))
    }
  }
}

case class ErrorResponse(error: String)

case class JsonCommand(name: String, data: Option[Json])
