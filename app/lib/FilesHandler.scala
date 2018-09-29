package lib

import java.util.concurrent.atomic.AtomicInteger

import better.files.File
import cats.data.EitherT
import cats.syntax.all._
import com.avast.metrics.scalaapi.Monitor
import com.typesafe.scalalogging.StrictLogging
import controllers.{WsApiController, WsMessage}
import io.circe.generic.extras.auto._
import io.circe.syntax._
import javax.inject.{Inject, Named}
import lib.App._
import lib.CirceImplicits._
import lib.serverapi.UploadResponse
import monix.eval.Task
import monix.execution.Scheduler
import monix.reactive.Observable
import utils.ConfigProperty

class FilesHandler @Inject()(cloudConnector: CloudConnector,
                             filesRegistry: CloudFilesRegistry,
                             wsApiController: WsApiController,
                             settings: Settings,
                             @ConfigProperty("fileHandler.uploadParallelism") uploadParallelism: Int,
                             @ConfigProperty("fileHandler.retries") retries: Int,
                             @Named("FilesHandler") monitor: Monitor)(implicit sch: Scheduler)
    extends AutoCloseable
    with StrictLogging {

  private val uploadingCnt = new AtomicInteger(0)

  monitor.gauge("uploading")(() => uploadingCnt.get())
  private val uploadedMeter = monitor.meter("uploaded")
  private val uploadedFailedMeter = monitor.meter("uploaded")

  private val uploadSemaphore = fs2.async.semaphore[Task](uploadParallelism).toIO.unsafeRunSync()

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

  def uploadNow(file: File)(implicit session: ServerSession): Result[List[UploadResponse]] = {
    if (file.isDirectory) {
      uploadDir(file)
    } else {
      withSemaphore {
        logger.debug(s"Uploading ${uploadingCnt.incrementAndGet()} files now")

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
              uploadingCnt.decrementAndGet()

            case Right(UploadResponse.Sha256Mismatch) =>
              logger.info(s"SHA256 mismatch while uploading file ${file.name}")
              uploadedFailedMeter.mark()
              uploadingCnt.decrementAndGet()

            case Left(appException) =>
              logger.info(s"Error while uploading file ${file.name}", appException)
              uploadedFailedMeter.mark()
              uploadingCnt.decrementAndGet()
          }
          .flatMap { r =>
            updateFilesRegistry(file, r)
              .map(_ => List(r))
          }
      }
    }
  }

  private def uploadDir(file: File)(implicit session: ServerSession): Result[List[UploadResponse]] =
    EitherT {
      Observable
        .fromIterator(file.children)
        .mapParallelUnordered(uploadParallelism)(uploadNow(_).value)
        .toListL
        .map(_.foldLeft[Either[AppException, List[UploadResponse]]](Right(List.empty)) { (prev, curr) =>
          for {
            p <- prev
            c <- curr
          } yield {
            c ++ p
          }
        })
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

  private def updateFilesRegistry(file: File, r: UploadResponse): Result[Unit] = {
    r match {
      case UploadResponse.Uploaded(remoteFile) => filesRegistry.updateFile(file, remoteFile)
      case _ => pureResult(())
    }
  }

  private def withSemaphore[A](a: => Result[A]): Result[A] = EitherT {
    (for {
      _ <- if (logger.underlying.isDebugEnabled) {
        uploadSemaphore.available.map(p => logger.debug(s"Available: $p"))
      } else Task.unit
      _ <- uploadSemaphore.decrement
      result <- a.value
    } yield {
      result
    }).transformWith(res => {
      uploadSemaphore.increment.map(_ => res)
    }, th => {
      uploadSemaphore.increment >> Task.raiseError(th)
    })
  }

  override def close(): Unit = ()
}

private sealed trait FileHandlingResult

private object FileHandlingResult {

  case object Handled extends FileHandlingResult

  case class ToBeUploaded(file: File) extends FileHandlingResult

}

private case class FileProgressUpdate(name: String, totalSize: Long, uploaded: Long, speed: Double)
