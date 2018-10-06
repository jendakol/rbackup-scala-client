package lib

import java.io.{ByteArrayInputStream, InputStream}
import java.net.ConnectException
import java.nio.file.AccessDeniedException
import java.util.concurrent.TimeoutException

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
import monix.execution.{Cancelable, Scheduler}
import org.http4s
import org.http4s.client.Client
import org.http4s.client.blaze.{BlazeClientConfig, Http1Client}
import org.http4s.headers.{`Content-Disposition`, `Content-Length`}
import org.http4s.multipart._
import org.http4s.{Headers, Method, Request, Response, Status, Uri}
import pureconfig.modules.http4s.uriReader
import pureconfig.{CamelCase, ConfigFieldMapping, ProductHint}

import scala.concurrent.Await
import scala.concurrent.duration._

class CloudConnector(httpClient: Client[Task], chunkSize: Int, scheduler: Scheduler) extends StrictLogging {

  // TODO retries
  // TODO monitoring

  def registerAccount(rootUri: Uri, username: String, password: String): Result[RegistrationResponse] = {
    logger.debug(s"Creating registration for username $username")

    exec(plainRequestToHost(Method.GET, rootUri, "account/register", Map("username" -> username, "password" -> password))) {
      case ServerResponse(Status.Created, Some(json)) => json.as[RegistrationResponse.Created].toResult
      case ServerResponse(Status.Conflict, _) => pureResult(RegistrationResponse.AlreadyExists)
    }
  }

  def login(rootUri: Uri, deviceId: String, username: String, password: String): Result[LoginResponse] = {
    logger.debug(s"Logging device $deviceId with username $username")

    val request =
      plainRequestToHost(Method.GET, rootUri, "account/login", Map("device_id" -> deviceId, "username" -> username, "password" -> password))

    exec(request) {
      case ServerResponse(Status.Created, Some(json)) =>
        json.hcursor.get[String]("session_id").toResult[String].map(sid => LoginResponse.SessionCreated(ServerSession(rootUri, sid)))
      case ServerResponse(Status.Ok, Some(json)) =>
        json.hcursor.get[String]("session_id").toResult[String].map(sid => LoginResponse.SessionRecovered(ServerSession(rootUri, sid)))
      case ServerResponse(Status.Unauthorized, _) =>
        pureResult(LoginResponse.Failed)
    }
  }

  def upload(file: File)(callback: (Long, Double, Boolean) => Unit)(implicit session: ServerSession): Result[UploadResponse] = {
    logger.debug(s"Uploading $file")

    stream("upload", file, callback, Map("file_path" -> file.path.toAbsolutePath.toString)) {
      case ServerResponse(Status.Ok, Some(json)) => json.as[RemoteFile].toResult.map(UploadResponse.Uploaded)
      case ServerResponse(Status.PreconditionFailed, _) => pureResult(UploadResponse.Sha256Mismatch)
    }
  }

  def download(fileVersion: RemoteFileVersion, dest: File)(implicit session: ServerSession): Result[DownloadResponse] = EitherT {
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

  def listFiles(specificDevice: Option[DeviceId])(implicit session: ServerSession): Result[ListFilesResponse] = {
    logger.debug(s"Getting files list for device $specificDevice")

    exec(authenticatedRequest(Method.GET, "list/files", specificDevice.map("device_id" -> _.value).toMap)) {
      case ServerResponse(Status.Ok, Some(json)) => json.as[Seq[RemoteFile]].toResult.map(FilesList)
      case ServerResponse(Status.NotFound, _) =>
        pureResult(ListFilesResponse.DeviceNotFound {
          specificDevice.getOrElse(throw new IllegalStateException("Must not be empty"))
        })
    }
  }

  def status(implicit session: ServerSession): Result[String] = {
    status(session.rootUri)
  }

  def status(rootUri: Uri): Result[String] = {
    logger.debug("Requesting status from server")

    exec(plainRequestToHost(Method.GET, rootUri, "status")) {
      case ServerResponse(Status.Ok, Some(json)) => json.hcursor.get[String]("status").toResult
    }
  }

  private def plainRequestToHost[A](method: Method,
                                    rootUri: Uri,
                                    path: String,
                                    queryParams: Map[String, String] = Map.empty): Request[Task] = {

    val uri = path.split("/").foldLeft(rootUri)(_ / _).setQueryParams(queryParams.mapValues(Seq(_)))

    Request[Task](
      method,
      uri
    )
  }

  private def plainRequest[A](method: Method, path: String, queryParams: Map[String, String] = Map.empty)(
      implicit session: ServerSession): Request[Task] = {
    plainRequestToHost(method, session.rootUri, path, queryParams)
  }

  private def authenticatedRequest[A](method: Method, path: String, queryParams: Map[String, String] = Map.empty)(
      implicit session: ServerSession): Request[Task] = {
    plainRequest(method, path, queryParams)
      .putHeaders(http4s.Header("RBackup-Session-Pass", session.sessionId))
  }

  private def stream[A](path: String, file: File, callback: (Long, Double, Boolean) => Unit, queryParams: Map[String, String] = Map.empty)(
      pf: PartialFunction[ServerResponse, Result[A]])(implicit session: ServerSession): Result[A] = {

    def createRequest(rootUri: Uri, inputStream: InputStreamWithSha256): EitherT[Task, AppException, Request[Task]] = {
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
                http4s.Header("RBackup-Session-Pass", session.sessionId)
              ))
            .withBody(data)
        }
    }

    import session._

    for {
      fis <- EitherT.rightT[Task, AppException](file.newInputStream)
      (cis, canc) = wrapWithStats(fis, callback(_, _, false))
      inputStream = new InputStreamWithSha256(cis)
      request <- createRequest(rootUri, inputStream)
      result <- exec(request)(pf).map { res => // cancel stats reporting and close the IS
        logger.debug(s"End stats sending for ${file.pathAsString}, file was uploaded")
        canc.cancel()
        inputStream.close()
        res
      }
    } yield {
      val (_, speed) = cis.snapshot
      logger.debug(s"File ${file.pathAsString} uploaded, sent finalizing stats to UI")
      callback(file.size, speed, true) // send final/last stats

      result
    }
  }

  private def wrapWithStats[A](fis: InputStream, callback: (Long, Double) => Unit): (StatsInputStream, Cancelable) = {
    val cis = new StatsInputStream(fis)(scheduler)

    val canc = scheduler.scheduleAtFixedRate(0.second, 1.second) {
      val (bytes, speed) = cis.snapshot
      callback(bytes, speed)
    }

    (cis, canc)
  }

  private def exec[A](request: Request[Task])(pf: PartialFunction[ServerResponse, Result[A]]): Result[A] = EitherT {
    logger.debug(s"Cloud request: $request")

    httpClient
      .fetch(request) { resp =>
        logger.debug(s"Cloud response: $resp")

        resp.bodyAsText.compile.toList
          .map(parts => if (parts.isEmpty) None else Some(parts.mkString))
          .flatMap { str =>
            logger.trace(s"Cloud response body: $str")

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

    new CloudConnector(httpClient, conf.chunkSize, sch)
  }
}

private case class CloudConnectorConfiguration(chunkSize: Int,
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
