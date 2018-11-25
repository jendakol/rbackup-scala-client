package lib

import java.util.concurrent.atomic.AtomicInteger

import better.files.File
import cats.data.EitherT
import com.avast.metrics.scalaapi.Monitor
import com.typesafe.scalalogging.StrictLogging
import controllers.{WsApiController, WsMessage}
import io.circe.generic.extras.auto._
import io.circe.syntax._
import javax.inject.{Inject, Named}
import lib.App._
import lib.CirceImplicits._
import lib.serverapi.{DownloadResponse, RemoteFile, RemoteFileVersion, UploadResponse}
import lib.settings.Settings
import monix.eval.Task
import monix.execution.Scheduler
import monix.reactive.Observable
import utils.ConfigProperty

class FilesHandler @Inject()(cloudConnector: CloudConnector,
                             filesRegistry: CloudFilesRegistry,
                             wsApiController: WsApiController,
                             dao: Dao,
                             settings: Settings,
                             @ConfigProperty("fileHandler.parallelism") maxParallelism: Int,
                             @ConfigProperty("fileHandler.retries") retries: Int,
                             @Named("FilesHandler") monitor: Monitor)(implicit sch: Scheduler)
    extends AutoCloseable
    with StrictLogging {

  private val transferringCnt = new AtomicInteger(0)
  monitor.gauge("transferring")(() => transferringCnt.get())

  private val uploadedMeter = monitor.meter("uploaded")
  private val uploadedFailedMeter = monitor.meter("uploadFailures")

  private val downloadedMeter = monitor.meter("downloaded")
  private val downloadedFailedMeter = monitor.meter("downloadFailures")

  private val semaphore = fs2.async.semaphore[Task](maxParallelism).toIO.unsafeRunSync()

  def download(remoteFile: RemoteFile, remoteFileVersion: RemoteFileVersion, dest: File)(
      implicit session: ServerSession): Result[List[DownloadResponse]] = {
    // TODO support DIRs download

    withSemaphore {
      logger.debug(s"Downloading ${transferringCnt.incrementAndGet()} files now")

      val attemptCounter = new AtomicInteger(0)

      cloudConnector
        .download(remoteFileVersion, dest) { (downloadedBytes, speed, isFinal) =>
          wsApiController
            .send(
              WsMessage(
                `type` = "fileTransferUpdate",
                data = FileProgressUpdate(dest.pathAsString,
                                          `type` = "Downloading",
                                          if (isFinal) "done" else "downloading",
                                          Some(remoteFileVersion.size),
                                          Some(downloadedBytes),
                                          Some(speed)).asJson
              )
            )
            .runAsync {
              case Left(ex) => logger.debug("Could not send file download update", ex)
            }
        }
        .doOnCancel(Task {
          logger.debug(s"Task was cancelled: download of ${dest.pathAsString}")
          downloadedFailedMeter.mark()
          transferringCnt.decrementAndGet()
        })
        .restartIf(handleRetries(attemptCounter.incrementAndGet(), dest))
        .withResult {
          case Right(DownloadResponse.Downloaded(file, _)) =>
            logger.debug(s"File ${file.pathAsString} downloaded")
            downloadedMeter.mark()
            transferringCnt.decrementAndGet()

          case Right(DownloadResponse.FileVersionNotFound(_)) =>
            logger.info(s"Version not found for file ${dest.pathAsString} on server")
            downloadedFailedMeter.mark()
            transferringCnt.decrementAndGet()

          case Left(appException) =>
            logger.info(s"Error while downloading file ${dest.pathAsString}", appException)
            downloadedFailedMeter.mark()
            transferringCnt.decrementAndGet()
        }
        .map(List(_)) // TODO this is hack ;-)
    }
  }

  def upload(file: File)(implicit session: ServerSession): Result[List[Option[UploadResponse]]] = {
    if (file.isDirectory) {
      uploadDir(file)
    } else {
      shouldUpload(file).flatMap {
        case false =>
          logger.debug(s"File $file was not updated, will NOT be uploaded")
          pureResult(List(None))

        case true =>
          logger.debug(s"File $file updated (or settings override that), will be uploaded")

          withSemaphore {
            logger.debug(s"Uploading ${transferringCnt.incrementAndGet()} files now")

            val attemptCounter = new AtomicInteger(0)

            cloudConnector
              .upload(file) { (uploadedBytes, speed, isFinal) =>
                wsApiController
                  .send(
                    WsMessage(
                      `type` = "fileTransferUpdate",
                      data = FileProgressUpdate(file.pathAsString,
                                                `type` = "Uploading",
                                                if (isFinal) "done" else "uploading",
                                                Some(file.size),
                                                Some(uploadedBytes),
                                                Some(speed)).asJson
                    )
                  )
                  .runAsync {
                    case Left(ex) => logger.debug("Could not send file upload update", ex)
                  }
              }
              .doOnCancel(Task {
                logger.debug(s"Task was cancelled: upload of ${file.pathAsString}")
                uploadedFailedMeter.mark()
                transferringCnt.decrementAndGet()
              })
              .restartIf(handleRetries(attemptCounter.incrementAndGet(), file))
              .withResult {
                case Right(UploadResponse.Uploaded(_)) =>
                  logger.debug(s"File ${file.pathAsString} uploaded")
                  uploadedMeter.mark()
                  transferringCnt.decrementAndGet()

                case Right(UploadResponse.Sha256Mismatch) =>
                  logger.info(s"SHA256 mismatch while uploading file ${file.pathAsString}")
                  uploadedFailedMeter.mark()
                  transferringCnt.decrementAndGet()

                case Left(appException) =>
                  logger.info(s"Error while uploading file ${file.pathAsString}", appException)
                  uploadedFailedMeter.mark()
                  transferringCnt.decrementAndGet()
              }
              .flatMap { r =>
                updateFilesRegistry(file, r)
                  .map(_ => List(Option(r)))
              }
          }
      }
    }
  }

  private def uploadDir(file: File)(implicit session: ServerSession): Result[List[Option[UploadResponse]]] =
    EitherT {
      Observable
        .fromIterator(file.children)
        .mapParallelUnordered(maxParallelism)(upload(_).value)
        .toListL
        .map(_.foldLeft[Either[AppException, List[Option[UploadResponse]]]](Right(List.empty)) { (prev, curr) =>
          for {
            p <- prev
            c <- curr
          } yield {
            c ++ p
          }
        })
        .cancelable
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

  private def shouldUpload(file: File): Result[Boolean] = {
    checkFileUpdated(file)
      .flatMap {
        case false =>
          logger.debug(s"File $file not updated, is 'upload_same_content' turned on?")
          settings.uploadSameContent

        case true => pureResult(true)
      }
  }

  private def checkFileUpdated(file: File): Result[Boolean] = {
    dao.getFile(file).map {
      case Some(dbFile) if sameFile(file, dbFile) =>
        logger.debug(s"File $file found in cache, is the same file -> nothing to do")
        false

      case Some(dbFile) =>
        logger.debug(s"File $file found in cache with different content ($dbFile)")
        true

      case None =>
        logger.debug(s"File $file wasn't found in DB")
        true
    }
  }

  private def sameFile(file: File, dbFile: DbFile): Boolean = {
    logger.debug(s"Is it same file - ${file.lastModifiedTime} vs. ${dbFile.lastModified}")
    dbFile.lastModified.toInstant == file.lastModifiedTime && dbFile.size == file.size
  }

  private def withSemaphore[A](a: => Result[A]): Result[A] = EitherT {
    semaphore.decrement.bracketE { _ =>
      a.value
    } {
      case (_, Right(Right(res))) =>
        logger.trace(s"File transfer result $res, unlocking")
        semaphore.increment
          .map(_ => logger.debug(s"Currently transferring ${transferringCnt.get()} files"))

      case (_, Right(Left(ae))) =>
        logger.trace(s"File transfer failed, unlocking", ae)
        semaphore.increment
          .map(_ => logger.debug(s"Currently transferring ${transferringCnt.get()} files"))

      case (_, Left(Some(th))) =>
        logger.trace(s"File transfer failed, unlocking", th)
        semaphore.increment
          .map(_ => logger.debug(s"Currently transferring ${transferringCnt.get()} files"))

      case (_, Left(None)) =>
        logger.trace(s"File transfer cancelled, unlocking")
        semaphore.increment
          .map(_ => logger.debug(s"Currently transferring ${transferringCnt.get()} files"))
    }
  }

  override def close(): Unit = ()
}

private sealed trait FileHandlingResult

private object FileHandlingResult {

  case object Handled extends FileHandlingResult

  case class ToBeUploaded(file: File) extends FileHandlingResult

}

private case class FileProgressUpdate(name: String,
                                      `type`: String,
                                      status: String,
                                      totalSize: Option[Long] = None,
                                      transferred: Option[Long] = None,
                                      speed: Option[Double] = None)
