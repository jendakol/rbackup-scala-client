package lib

import java.util.concurrent.atomic.AtomicInteger

import better.files.File
import com.avast.metrics.scalaapi.Monitor
import com.typesafe.scalalogging.StrictLogging
import controllers.{WsApiController, WsMessage}
import io.circe.generic.extras.auto._
import io.circe.syntax._
import javax.inject.{Inject, Named}
import lib.App._
import lib.CirceImplicits._
import lib.serverapi.UploadResponse
import monix.execution.Scheduler
import utils.ConfigProperty

class FilesHandler @Inject()(cloudConnector: CloudConnector,
                             wsApiController: WsApiController,
                             settings: Settings,
                             @ConfigProperty("fileHandler.uploadParallelism") uploadParallelism: Int,
                             @ConfigProperty("fileHandler.retries") retries: Int,
                             @Named("FilesHandler") monitor: Monitor)(implicit sch: Scheduler)
    extends AutoCloseable
    with StrictLogging {

  private val uploadedMeter = monitor.meter("uploaded")
  private val uploadedFailedMeter = monitor.meter("uploaded")

  //  def uploadLater(file: File): Task[Unit] = ???
  //
  //  private def handleFile(file: File): Task[FileHandlingResult] = {}
  //
  //  private def upload(fileResult: FileHandlingResult)(implicit sessionId: SessionId): Task[Unit] = fileResult match {
  //    case FileHandlingResult.ToBeUploaded(file) =>
  //      upload(file)
  //
  //    case FileHandlingResult.Handled => Task.unit
  //  }

  def uploadNow(file: File)(implicit session: ServerSession): Result[UploadResponse] = {
    val attemptCounter = new AtomicInteger(0)

    cloudConnector
      .upload(file) { (uploadedBytes, speed) =>
        wsApiController
          .send(
            WsMessage(`type` = "fileUploadUpdate", data = FileProgressUpdate(file.pathAsString, file.size, uploadedBytes, speed).asJson)
          )
          .runAsync {
            case Left(ex) => logger.debug("Could not send file upload update", ex)
          }
      }
      .restartIf(handleRetries(attemptCounter.incrementAndGet(), file))
      .withResult {
        case Right(UploadResponse.Uploaded(_)) =>
          logger.debug(s"File ${file.name} uploaded")
          uploadedMeter.mark()

        case Right(UploadResponse.Sha256Mismatch) =>
          logger.info(s"SHA256 mismatch while uploading file ${file.name}")
          uploadedFailedMeter.mark()

        case Left(appException) =>
          logger.info(s"Error while uploading file ${file.name}", appException)
          uploadedFailedMeter.mark()
      }
  }

  private def handleRetries(attemptNo: Int, file: File)(t: Throwable): Boolean = {
    if (attemptNo >= retries + 1) {
      logger.debug(s"Not retrying anymore ($attemptNo attempts) for file ${file.name}", t)
      false
    } else {
      logger.debug(s"Retrying ($attemptNo attempts so far) file ${file.name}", t)
      true // TODO don't retry always
    }
  }

  override def close(): Unit = ()
}

private sealed trait FileHandlingResult

private object FileHandlingResult {

  case object Handled extends FileHandlingResult

  case class ToBeUploaded(file: File) extends FileHandlingResult

}

private case class FileProgressUpdate(name: String, totalSize: Long, uploaded: Long, speed: Double)
