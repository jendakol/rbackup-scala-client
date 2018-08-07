package lib

import cats.data.EitherT
import com.typesafe.config.{Config, ConfigFactory}
import com.typesafe.scalalogging.StrictLogging
import io.circe.Json
import io.circe.generic.extras.auto._
import lib.App._
import lib.CirceImplicits._
import lib.serverapi.{LoginResponse, RegistrationResponse}
import monix.eval.Task
import monix.execution.Scheduler
import org.http4s.client.Client
import org.http4s.client.blaze.{BlazeClientConfig, Http1Client}
import org.http4s.{Method, Request, Status, Uri}
import pureconfig.modules.http4s.uriReader
import pureconfig.{CamelCase, ConfigFieldMapping, ProductHint}

import scala.concurrent.Await
import scala.concurrent.duration.Duration

class CloudConnector(rootUri: Uri, httpClient: Client[Task]) extends StrictLogging {
  def registerAccount(username: String, password: String): Result[RegistrationResponse] = {
    logger.debug(s"Creating registration for username $username")

    send(Method.GET, "account/register", Map("username" -> username, "password" -> password)) {
      case ServerResponse(Status.Created, Some(json)) => json.as[RegistrationResponse.Created].toResult
      case ServerResponse(Status.Conflict, _) => pureResult(RegistrationResponse.AlreadyExists)
    }
  }

  def login(deviceId: String, username: String, password: String): Result[LoginResponse] = {
    logger.debug(s"Logging device $deviceId with username $username")

    send(Method.GET, "account/login", Map("device_id" -> deviceId, "username" -> username, "password" -> password)) {
      case ServerResponse(Status.Created, Some(json)) => json.as[LoginResponse.SessionCreated].toResult
      case ServerResponse(Status.Ok, Some(json)) => json.as[LoginResponse.SessionRecovered].toResult
      case ServerResponse(Status.Unauthorized, _) => pureResult(LoginResponse.Failed)
    }
  }

  def status: Result[String] = {
    logger.debug("Requesting status from server")

    send(Method.GET, "status") {
      case ServerResponse(Status.Created, Some(json)) => json.hcursor.get[String]("status").toResult
    }
  }

  private def send[A](method: Method, path: String, queryParams: Map[String, String] = Map.empty)(
      pf: PartialFunction[ServerResponse, Result[A]]): Result[A] = EitherT {

    val uri = path.split("/").foldLeft(rootUri)(_ / _).setQueryParams(queryParams.mapValues(Seq(_)))

    val request = Request[Task](
      method,
      uri
    )

    logger.debug(s"Cloud request: $request")

    httpClient.fetch(request) { resp =>
      resp.bodyAsText.compile.last.flatMap { str =>
        val jsonResult = str.map(io.circe.parser.parse) match {
          case Some(Right(json)) => pureResult(Some(json))
          case None => pureResult(None)
          case Some(Left(err)) =>
            failedResult {
              AppException.InvalidResponseException(
                resp.status.code,
                str.toString,
                "Invalid JSON in body",
                AppException.ParsingFailure(str.toString, err)
              )
            }
        }

        jsonResult
          .flatMap[AppException, A] { json =>
            val serverResponse = ServerResponse(resp.status, json)

            pf.applyOrElse(
              serverResponse,
              (_: ServerResponse) => {
                failedResult(AppException.InvalidResponseException(resp.status.code, str.toString, "Unexpected status or content"))
              }
            )
          }
          .value
      }
    }
  }
}

case class ServerResponse(status: Status, body: Option[Json])

object CloudConnector {
  private val RootConfigKey = "cloudConnectorDefaults"
  private val DefaultConfig = ConfigFactory.defaultReference().getConfig(RootConfigKey)

  // configure pureconfig:
  private implicit val ph: ProductHint[CloudConnectorConfiguration] = ProductHint[CloudConnectorConfiguration](
    fieldMapping = ConfigFieldMapping(CamelCase, CamelCase)
  )

  def fromConfig(config: Config)(implicit sch: Scheduler): CloudConnector = {
    val conf = pureconfig.loadConfigOrThrow[CloudConnectorConfiguration](config.withFallback(DefaultConfig))
    val httpClient: Client[Task] = Await.result(Http1Client[Task](conf.toBlazeConfig.copy(executionContext = sch)).runAsync, Duration.Inf)

    new CloudConnector(conf.uri, httpClient)
  }
}

private case class CloudConnectorConfiguration(uri: Uri,
                                               requestTimeout: Duration,
                                               socketTimeout: Duration,
                                               responseHeaderTimeout: Duration,
                                               maxConnections: Int) {
  def toBlazeConfig: BlazeClientConfig = BlazeClientConfig.defaultConfig.copy(
    requestTimeout = requestTimeout,
    maxTotalConnections = maxConnections,
    responseHeaderTimeout = responseHeaderTimeout,
    idleTimeout = socketTimeout,
    userAgent = Option {
      org.http4s.headers.`User-Agent`
        .parse("RBackup client")
        .getOrElse(throw new IllegalArgumentException("Unsupported format of user-agent provided"))
    }
  )
}
