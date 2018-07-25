package controllers

import akka.actor._
import akka.stream.Materializer
import com.typesafe.scalalogging.StrictLogging
import io.circe.Json
import io.circe.generic.auto._
import io.circe.parser._
import io.circe.syntax._
import javax.inject._
import lib.{Command, CommandExecutor}
import play.api.libs.circe.Circe
import play.api.libs.streams.ActorFlow
import play.api.mvc._
import utils.AllowedApiOrigins

import scala.concurrent.Future
import scala.util.Try

@Singleton
class WsApiController @Inject()(cc: ControllerComponents,
                                commandExecutor: CommandExecutor,
                                protected override val allowedOrigins: AllowedApiOrigins)(implicit system: ActorSystem, mat: Materializer)
    extends AbstractController(cc)
    with Circe
    with SameOriginCheck
    with StrictLogging {

  def socket: WebSocket = WebSocket.acceptOrResult[String, String] {
    case rh if sameOriginCheck(rh) =>
      Future.successful {
        Right {
          ActorFlow.actorRef { out =>
            Props(new WebSocketApiActor(out))
          }
        }
      }

    case rejected =>
      logger.info(s"Request '$rejected' failed same origin check")

      Future.successful {
        Left(Forbidden("forbidden"))
      }
  }

  private def handle(out: ActorRef, jsonCommand: JsonCommand): Unit = {
    Command(jsonCommand.name, jsonCommand.data) match {
      case Some(command) =>
        commandExecutor.execute(command, json => {
          val response = json.noSpaces
          logger.debug(s"Sending message: $response")

          Try {
            out ! response
          }
        })
      case None => out ! ErrorResponse("Command not found").asJson.noSpaces
    }
  }

  class WebSocketApiActor(out: ActorRef) extends Actor with StrictLogging {
    def receive: Actor.Receive = {
      case content: String =>
        logger.debug(s"Received message: $content")

        decode[JsonCommand](content) match {
          case Right(command) => handle(out, command)
          case Left(e) => out ! ErrorResponse(s"${e.getClass.getName}: ${e.getMessage}").asJson.noSpaces
        }
    }
  }

}

private case class JsonCommand(name: String, data: Option[Json])

private case class ErrorResponse(error: String)
