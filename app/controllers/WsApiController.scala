package controllers

import java.util.concurrent.atomic.AtomicReference

import akka.actor._
import akka.stream.Materializer
import cats.data.EitherT
import cats.syntax.either._
import com.typesafe.scalalogging.StrictLogging
import controllers.WsApiController._
import io.circe.generic.extras.auto._
import io.circe.syntax._
import io.circe.{Json, Printer}
import javax.inject._
import lib.App
import lib.App._
import lib.AppException.{ParsingFailure, WsException}
import lib.CirceImplicits._
import monix.eval.Task
import monix.execution.Scheduler
import play.api.libs.circe.Circe
import play.api.libs.streams.ActorFlow
import play.api.mvc._
import utils.AllowedWsApiOrigins

import scala.concurrent.Future
import scala.util.Try
import scala.util.control.NonFatal

@Singleton
class WsApiController @Inject()(cc: ControllerComponents, protected override val allowedOrigins: AllowedWsApiOrigins)(
    implicit system: ActorSystem,
    mat: Materializer,
    sch: Scheduler)
    extends AbstractController(cc)
    with Circe
    with SameOriginCheck
    with StrictLogging {

  private var eventsCallback: Option[Event => App.Result[Unit]] = None

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

  def send(wsMessage: WsMessage): lib.App.Result[Unit] = {
    EitherT {
      Task {
        out.get() match {
          case Some(o) =>
            Try {
              logger.debug(s"Sending WS message through $o: $wsMessage")

              o ! wsMessage.asJson.pretty(jsonPrinter)
            }.toEither
              .leftMap(WsException("Could not send WS message", _))

          case None => Left(WsException("Could not send WS message - connection not available"))
        }
      }
    }
  }

  def send(`type`: String, data: Json): lib.App.Result[Unit] = send(WsMessage(`type`, data))

  def setEventCallback(callback: Event => App.Result[Unit]): Unit = {
    this.eventsCallback = Option(callback)
  }

  private class WebSocketApiActor(out: ActorRef) extends Actor with StrictLogging {
    def receive: Actor.Receive = {
      case content: String =>
        logger.debug(s"Received WS message: $content")
        // TODO

        logger.info(s"Updating WS channel to $out")
        WsApiController.out.set(Option(out))

        eventsCallback.foreach { callback =>
          EitherT(
            Task[Either[io.circe.Error, Event]](
              for {
                json <- io.circe.parser.parse(content)
                cursor = json.hcursor
                eventType <- cursor.get[String]("type")
                event <- eventType match {
                  case "init" => cursor.get[InitEvent]("data")
                }
              } yield {
                event
              }
            ))
            .leftMap(ParsingFailure(content, _))
            .flatMap(callback)
            .runAsync {
              case Left(NonFatal(e)) => logger.warn(s"Could not propagate WS event $content", e)
            }
        }
    }
  }

}

object WsApiController {
  private val jsonPrinter: Printer = Printer.noSpaces.copy(dropNullValues = true)

  // shared across all potential instances (which should not happen)
  private val out: AtomicReference[Option[ActorRef]] = new AtomicReference[Option[ActorRef]](None)
}

case class WsMessage(`type`: String, data: Json)

sealed trait Event

case class InitEvent(page: String) extends Event
