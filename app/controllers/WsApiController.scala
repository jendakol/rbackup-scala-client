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
import lib.App._
import lib.AppException.{ParsingFailure, WsException}
import lib.{App, AppException}
import monix.eval.Task
import monix.execution.Scheduler
import play.api.libs.circe.Circe
import play.api.libs.streams.ActorFlow
import play.api.mvc._
import utils.AllowedWsApiOrigins
import utils.CirceImplicits._

import scala.concurrent.Future
import scala.concurrent.duration._
import scala.util.Try
import scala.util.control.NonFatal

@Singleton
class WsApiController @Inject()(
    cc: ControllerComponents,
    protected override val allowedOrigins: AllowedWsApiOrigins,
    @Named("blocking") blockingScheduler: Scheduler)(implicit system: ActorSystem, mat: Materializer, sch: Scheduler)
    extends AbstractController(cc)
    with Circe
    with SameOriginCheck
    with StrictLogging {

  private var eventsCallback: Option[Event => App.Result[Unit]] = None

  sch.scheduleAtFixedRate(0.seconds, 1.second) {
    out.get() match {
      case Some(o) =>
        try {
          logger.trace(s"Sending WS heartbeat through $o")

          o ! WsMessage("heartbeat", Json.Null).asJson.pretty(jsonPrinter)
        } catch {
          case NonFatal(e) => logger.warn("Could not send WS message", e)
        }

      case None => logger.trace("Could not send WS heartbeat - connection not available")
    }
  }

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
      logger.info(s"Request '$rejected' - failed same origin check")

      Future.successful {
        Left(Forbidden("forbidden"))
      }
  }

  def send(wsMessage: WsMessage, ignoreFailure: Boolean): lib.App.Result[Unit] = {
    EitherT {
      Task {
        out.get() match {
          case Some(o) =>
            Try {
              logger.trace(s"Sending WS message through $o: $wsMessage")

              o ! wsMessage.asJson.pretty(jsonPrinter)
              ()
            }.toEither
              .leftMap(WsException("Could not send WS message", _))

          case None => Left(WsException("Could not send WS message - connection not available"))
        }
      }.executeOnScheduler(blockingScheduler): Task[Either[AppException, Unit]]
    }.recoverWith {
      case NonFatal(e) if ignoreFailure =>
        logger.debug("Ignoring failure in WS message send", e)
        pureResult(())
    }
  }

  def send(`type`: String, data: Json, ignoreFailure: Boolean): lib.App.Result[Unit] = send(WsMessage(`type`, data), ignoreFailure)

  def setEventCallback(callback: Event => App.Result[Unit]): Unit = {
    this.eventsCallback = Option(callback)
  }

  private class WebSocketApiActor(out: ActorRef) extends Actor with StrictLogging {
    def receive: Actor.Receive = {
      case content: String =>
        logger.debug(s"Received WS message: $content")

        eventsCallback.foreach { callback =>
          EitherT(
            Task[Either[io.circe.Error, Event]](
              for {
                json <- io.circe.parser.parse(content)
                cursor = json.hcursor
                eventType <- cursor.get[String]("type")
                event <- eventType match {
                  case "init" =>
                    logger.info(s"Opened UI, setting WS channel to $out")
                    WsApiController.out.set(Some(out))
                    Right(InitEvent)

                  case "close" =>
                    logger.info(s"Closed UI, setting WS channel to NULL")
                    WsApiController.out.set(None)
                    Right(CloseEvent)

                  case "page-init" =>
                    cursor.get[PageInitEvent]("data")
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

case object InitEvent extends Event

case class PageInitEvent(page: String) extends Event

case object CloseEvent extends Event
