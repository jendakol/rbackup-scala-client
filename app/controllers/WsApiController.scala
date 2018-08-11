package controllers

import java.util.concurrent.atomic.AtomicReference

import akka.actor._
import akka.stream.Materializer
import cats.data.EitherT
import cats.syntax.either._
import com.typesafe.scalalogging.StrictLogging
import io.circe.Json
import io.circe.generic.extras.auto._
import io.circe.syntax._
import javax.inject._
import lib.AppException.WsException
import lib.CirceImplicits._
import monix.eval.Task
import play.api.libs.circe.Circe
import play.api.libs.streams.ActorFlow
import play.api.mvc._
import utils.AllowedApiOrigins

import scala.concurrent.Future
import scala.util.Try

@Singleton
class WsApiController @Inject()(cc: ControllerComponents, protected override val allowedOrigins: AllowedApiOrigins)(
    implicit system: ActorSystem,
    mat: Materializer)
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

  private val out: AtomicReference[Option[ActorRef]] = new AtomicReference[Option[ActorRef]](None)

  def send(wsMessage: WsMessage): lib.App.Result[Unit] = {
    EitherT {
      Task {
        out.get() match {
          case Some(o) =>
            Try {
              logger.trace(s"Sending WS message: $wsMessage")

              o ! wsMessage.asJson.noSpaces
            }.toEither
              .leftMap(WsException("Could not send WS message", _))

          case None => Left(WsException("Could not send WS message - connection not available"))
        }
      }
    }
  }

  private class WebSocketApiActor(out: ActorRef) extends Actor with StrictLogging {
    def receive: Actor.Receive = {
      case content: String =>
        logger.trace(s"Received WS message: $content")
        // TODO

        WsApiController.this.out.set(Option(out))
    }
  }

}

case class WsMessage(`type`: String, data: Json)
