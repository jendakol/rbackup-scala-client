package controllers

import akka.actor.ActorSystem
import akka.stream.Materializer
import com.typesafe.scalalogging.StrictLogging
import controllers.AjaxApiController._
import io.circe.generic.auto._
import io.circe.syntax._
import io.circe.{Json, Printer}
import javax.inject.{Inject, Singleton}
import lib.commands.{Command, CommandExecutor}
import lib.{App, AppException}
import monix.execution.Scheduler
import play.api.libs.circe.Circe
import play.api.mvc.{AbstractController, Action, ControllerComponents}

import scala.concurrent.Future

@Singleton
class AjaxApiController @Inject()(cc: ControllerComponents, commandExecutor: CommandExecutor)(implicit system: ActorSystem,
                                                                                              mat: Materializer,
                                                                                              sch: Scheduler)
    extends AbstractController(cc)
    with Circe
    with StrictLogging {

  def exec: Action[JsonCommand] = Action.async(circe.tolerantJson[JsonCommand]) { req =>
    val jsonCommand = req.body

    logger.debug(s"Received AJAX request: ${jsonCommand.asJson.noSpaces}")

    App.leaveBreadcrumb("Received AJAX request",
                        Map(
                          "data" -> jsonCommand.asJson.noSpaces
                        ))

    decodeCommand(jsonCommand) match {
      case Right(command) =>
        commandExecutor
          .execute(command)
          .value
          .map {
            case Right(json) =>
              Ok {
                val str = json.pretty(JsonPrinter)
                logger.debug(s"Sending AJAX response to ${jsonCommand.name}: $str")
                App.leaveBreadcrumb("AJAX response", Map("data" -> str))
                str
              }.as("application/json")

            case Left(AppException.InvalidResponseException(_, _, desc, cause)) =>
              logger.info(s"Error while executing the command ${jsonCommand.name}", cause)
              val resp = ErrorResponse(desc).asJson.pretty(JsonPrinter)
              logger.debug(s"Sending AJAX error response to ${jsonCommand.name}: $resp")
              App.leaveBreadcrumb("AJAX error response", Map("data" -> resp))
              BadRequest(resp)

            case Left(err) =>
              logger.info(s"Error while executing the command ${jsonCommand.name}", err)
              val resp = ErrorResponse(err).asJson.pretty(JsonPrinter)
              logger.debug(s"Sending AJAX error response to ${jsonCommand.name}: $resp")
              App.leaveBreadcrumb("AJAX error response", Map("data" -> resp))
              BadRequest(resp)

          }
          .runAsync

      case Left(err) =>
        val resp = err.asJson.pretty(JsonPrinter)
        logger.debug(s"Sending AJAX error response to ${jsonCommand.name}: $resp", err)
        App.leaveBreadcrumb("AJAX error response", Map("data" -> resp))
        Future.successful(BadRequest(resp))
    }
  }

  private def decodeCommand(jsonCommand: JsonCommand): Either[ErrorResponse, Command] = {
    import jsonCommand._

    Command(name, data) match {
      case Some(command) => Right(command)
      case None =>
        App.leaveBreadcrumb("Unparsable JSON command", Map("data" -> jsonCommand.asJson.noSpaces))
        Left(ErrorResponse("Command not found or is missing some data"))
    }
  }
}

object AjaxApiController {
  final val JsonPrinter: Printer = Printer.noSpaces.copy(dropNullValues = true)
}

case class ErrorResponse(error: String)

object ErrorResponse {
  def apply(t: Throwable): ErrorResponse = {
    ErrorResponse(s"${t.getClass.getName}: ${t.getMessage}")
  }
}

case class JsonCommand(name: String, data: Option[Json])
