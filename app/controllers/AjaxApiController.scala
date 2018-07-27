package controllers

import akka.actor.ActorSystem
import akka.stream.Materializer
import com.typesafe.scalalogging.StrictLogging
import io.circe.generic.auto._
import io.circe.syntax._
import javax.inject.{Inject, Singleton}
import lib.CommandExecutor
import play.api.libs.circe.Circe
import play.api.mvc.{AbstractController, ControllerComponents}

import scala.concurrent.{ExecutionContext, Future, Promise}
import scala.util.Try

@Singleton
class AjaxApiController @Inject()(cc: ControllerComponents, commandExecutor: CommandExecutor)(implicit system: ActorSystem,
                                                                                              mat: Materializer,
                                                                                              ec: ExecutionContext)
    extends AbstractController(cc)
    with ApiController
    with Circe
    with StrictLogging {

  def exec = Action.async(circe.tolerantJson[JsonCommand]) { req =>
    val jsonCommand = req.body

    logger.debug(s"Received AJAX request: $jsonCommand")

    decodeCommand(jsonCommand) match {
      case Right(command) =>
        val promise = Promise[String]()

        commandExecutor.execute(command, json => {
          val response = json.noSpaces
          logger.debug(s"Sending AJAX response: $response")

          Try {
            promise.complete(Try(json.asJson.noSpaces))
          }
        })

        promise.future.map(Ok(_).as("application/json"))

      case Left(err) => Future.successful(BadRequest(err.asJson.noSpaces))
    }
  }
}
