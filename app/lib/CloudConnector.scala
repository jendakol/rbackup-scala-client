package lib

import java.io.{ByteArrayInputStream, InputStream}
import java.net.ConnectException
import java.nio.file.AccessDeniedException
import java.util.concurrent.TimeoutException
import java.util.concurrent.atomic.AtomicLong

import better.files.File
import cats.data.EitherT
import com.typesafe.config.{Config, ConfigFactory}
import com.typesafe.scalalogging.StrictLogging
import fs2.Stream
import fs2.text.utf8Encode
import io.circe.Json
import io.circe.generic.extras.auto._
import lib.App._
import lib.AppException.ServerNotResponding
import lib.CirceImplicits._
import lib.serverapi.ListFilesResponse.FilesList
import lib.serverapi._
import monix.eval.Task
import monix.execution.Scheduler
import org.http4s
import org.http4s.client.Client
import org.http4s.client.blaze.{BlazeClientConfig, Http1Client}
import org.http4s.headers.{`Content-Disposition`, `Content-Length`}
import org.http4s.multipart._
import org.http4s.{Headers, Method, Request, Response, Status, Uri}
import pureconfig.modules.http4s.uriReader
import pureconfig.{CamelCase, ConfigFieldMapping, ProductHint}

import scala.concurrent.Await
import scala.concurrent.duration.Duration

class CloudConnector(rootUri: Uri, httpClient: Client[Task], chunkSize: Int) extends StrictLogging {

  // TODO retries
  // TODO monitoring

  def registerAccount(username: String, password: String): Result[RegistrationResponse] = {
    logger.debug(s"Creating registration for username $username")

    exec(plainRequest(Method.GET, "account/register", Map("username" -> username, "password" -> password))) {
      case ServerResponse(Status.Created, Some(json)) => json.as[RegistrationResponse.Created].toResult
      case ServerResponse(Status.Conflict, _) => pureResult(RegistrationResponse.AlreadyExists)
    }
  }

  def login(deviceId: String, username: String, password: String): Result[LoginResponse] = {
    logger.debug(s"Logging device $deviceId with username $username")

    exec(plainRequest(Method.GET, "account/login", Map("device_id" -> deviceId, "username" -> username, "password" -> password))) {
      case ServerResponse(Status.Created, Some(json)) => json.as[LoginResponse.SessionCreated].toResult
      case ServerResponse(Status.Ok, Some(json)) => json.as[LoginResponse.SessionRecovered].toResult
      case ServerResponse(Status.Unauthorized, _) => pureResult(LoginResponse.Failed)
    }
  }

  def upload(file: File)(callback: Long => Unit)(implicit sessionId: SessionId): Result[UploadResponse] = {
    logger.debug(s"Uploading $file")

    val bytesUploaded = new AtomicLong(0)

    def uploadCallback(b: Int): Unit = {
      callback.apply(bytesUploaded.addAndGet(b))
    }

    stream("upload", file, uploadCallback, Map("file_path" -> file.path.toAbsolutePath.toString)) {
      case ServerResponse(Status.Ok, Some(json)) => json.as[RemoteFile].toResult.map(UploadResponse.Uploaded)
      case ServerResponse(Status.PreconditionFailed, _) => pureResult(UploadResponse.Sha256Mismatch)
    }
  }

  def download(fileVersion: RemoteFileVersion, dest: File)(implicit sessionId: SessionId): Result[DownloadResponse] = EitherT {
    logger.debug(s"Downloading file $fileVersion")

    httpClient
      .fetch(authenticatedRequest(Method.GET, "download", Map("file_version_id" -> fileVersion.version.toString))) {
        case resp if resp.status == Status.Ok => receiveStreamedFile(fileVersion, dest, resp)
        case resp if resp.status == Status.NotFound => Task.now(Right(DownloadResponse.FileVersionNotFound(fileVersion)))
      }
      .onErrorRecover {
        case e: ConnectException => Left(ServerNotResponding(e))
        case e: TimeoutException => Left(ServerNotResponding(e))
      }
  }

  def listFiles(specificDevice: Option[DeviceId])(implicit sessionId: SessionId): Result[ListFilesResponse] = {
    logger.debug(s"Getting files list for device $specificDevice")

    exec(authenticatedRequest(Method.GET, "list/files", specificDevice.map("device_id" -> _.value).toMap)) {
      case ServerResponse(Status.Ok, Some(json)) => json.as[Seq[RemoteFile]].toResult.map(FilesList)
      case ServerResponse(Status.NotFound, _) =>
        pureResult(ListFilesResponse.DeviceNotFound {
          specificDevice.getOrElse(throw new IllegalStateException("Must not be empty"))
        })
    }
  }

  def status: Result[String] = {
    logger.debug("Requesting status from server")

    exec(plainRequest(Method.GET, "status")) {
      case ServerResponse(Status.Ok, Some(json)) => json.hcursor.get[String]("status").toResult
    }
  }

  private def plainRequest[A](method: Method, path: String, queryParams: Map[String, String] = Map.empty): Request[Task] = {
    val uri = path.split("/").foldLeft(rootUri)(_ / _).setQueryParams(queryParams.mapValues(Seq(_)))

    Request[Task](
      method,
      uri
    )
  }

  private def authenticatedRequest[A](method: Method, path: String, queryParams: Map[String, String] = Map.empty)(
      implicit sessionId: SessionId): Request[Task] = {
    plainRequest(method, path, queryParams)
      .putHeaders(http4s.Header("RBackup-Session-Pass", sessionId.value))
  }

  private def stream[A](path: String, file: File, callback: Int => Unit, queryParams: Map[String, String] = Map.empty)(
      pf: PartialFunction[ServerResponse, Result[A]])(implicit sessionId: SessionId): Result[A] = {

    EitherT
      .rightT[Task, AppException] {
        file.newInputStream
      }
      .map { i =>
        new InputStreamWithSha256(new CallbackInputStream(i)(callback))
      }
      .flatMap { inputStream =>
        val uri = path.split("/").foldLeft(rootUri)(_ / _).setQueryParams(queryParams.mapValues(Seq(_)))

        val data = Multipart[Task](
          Vector(
            Part.fileData("file", file.name, fs2.io.readInputStream(Task.now(inputStream: InputStream), chunkSize)),
            Part(
              Headers(`Content-Disposition`("form-data", Map("name" -> "file-hash"))),
              Stream.eval(inputStream.sha256).map(_.toString).through(utf8Encode)
            )
          )
        )

        EitherT
          .right[AppException] {
            Request[Task](
              Method.POST,
              uri
            ).withHeaders(
                data.headers.put(
                  http4s.Header("RBackup-Session-Pass", sessionId.value)
                ))
              .withBody(data)
          }
      }
      .flatMap(exec(_)(pf))
  }

  private def exec[A](request: Request[Task])(pf: PartialFunction[ServerResponse, Result[A]]): Result[A] = EitherT {
    logger.debug(s"Cloud request: $request")

    httpClient
      .fetch(request) { resp =>
        logger.debug(s"Cloud response: $resp")

        resp.bodyAsText.compile.toList
          .map(parts => if (parts.isEmpty) None else Some(parts.mkString))
          .flatMap { str =>
            logger.debug(s"Cloud response body: $str")

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
      .onErrorRecover {
        case e: ConnectException => Left(ServerNotResponding(e))
        case e: TimeoutException => Left(ServerNotResponding(e))
      }
  }

  private def receiveStreamedFile(fileVersion: RemoteFileVersion,
                                  dest: File,
                                  resp: Response[Task]): Task[Either[AppException, DownloadResponse]] = {
    if (!dest.isReadable || !dest.isWriteable) {
      logger.debug(s"File $dest is not readable or writeable!")
      Task.now(Left(AppException.AccessDenied(dest)))
    } else {
      `Content-Length`.from(resp.headers) match {
        case Some(clh) =>
          val fileCopier = new FileCopier

          Task {
            if (dest.exists) dest.delete()
            dest.newOutputStream()
          }.flatMap { fileOs =>
              resp.body.chunks
                .map { bytes =>
                  val bis = new ByteArrayInputStream(bytes.toArray)
                  val copied = fileCopier.copy(bis, fileOs)
                  bis.close()
                  copied
                }
                .compile
                .toVector
                .map { chunksSizes =>
                  val transferred = chunksSizes.sum

                  fileOs.close() // all data has been transferred

                  if (clh.length != transferred) {
                    Left(AppException
                      .InvalidResponseException(resp.status.code, "-stream-", s"Expected ${clh.length} B but got $transferred B"))
                  } else {
                    val transferredSha = fileCopier.finalSha256

                    if (transferredSha != fileVersion.hash) {
                      Left(
                        AppException
                          .InvalidResponseException(resp.status.code,
                                                    "-stream-",
                                                    s"Expected SHA256 ${fileVersion.hash} but got $transferredSha"))
                    } else {
                      Right(DownloadResponse.Downloaded(dest, fileVersion))
                    }
                  }
                }
            }
            .onErrorRecover {
              case e: AccessDeniedException =>
                logger.debug(s"Error while accessing file $dest", e)
                Left(AppException.AccessDenied(dest, e))
            }

        case None => Task.now(Left(AppException.InvalidResponseException(resp.status.code, "-stream-", "Missing Content-Length header")))
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

    new CloudConnector(conf.uri, httpClient, conf.chunkSize)
  }
}

private case class CloudConnectorConfiguration(uri: Uri,
                                               chunkSize: Int,
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
