package lib

import java.util.concurrent.atomic.AtomicInteger

import better.files.File
import cats.data.EitherT
import cats.effect.Effect
import com.avast.metrics.scalaapi.Monitor
import com.typesafe.scalalogging.StrictLogging
import controllers.{WsApiController, WsMessage}
import io.circe.generic.extras.auto._
import io.circe.syntax._
import javax.inject.{Inject, Named}
import lib.App._
import lib.AppException.InvalidArgument
import lib.db.{Dao, DbFile}
import lib.server.serverapi._
import lib.server.{CloudConnector, CloudFilesRegistry}
import lib.settings.Settings
import monix.eval.Task
import monix.execution.Scheduler
import monix.reactive.Observable
import utils.CirceImplicits._
import utils.ConfigProperty

class FilesHandler @Inject()(cloudConnector: CloudConnector,
                             filesRegistry: CloudFilesRegistry,
                             wsApiController: WsApiController,
                             dao: Dao,
                             settings: Settings,
                             @Named("blocking") blockingScheduler: Scheduler,
                             @ConfigProperty("fileHandler.parallelism") maxParallelism: Int,
                             @ConfigProperty("fileHandler.retries") retries: Int,
                             @Named("FilesHandler") monitor: Monitor)(implicit F: Effect[Task], sch: Scheduler)
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

    def sendProgressUpdate(downloadedBytes: Long, speed: Double, isFinal: Boolean): Unit = {
      if (isFinal) {
        logger.trace(s"Sending final progress update for ${remoteFile.originalName} download")
      } else {
        logger.trace(s"Sending non-final progress update for ${remoteFile.originalName} download: $downloadedBytes, $speed")
      }

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
          ),
          ignoreFailure = true
        )
        .runAsync {
          case Left(ex) => logger.debug("Could not send file download update", ex)
        }(blockingScheduler)
    }

    withSemaphore {
      logger.debug(s"Downloading ${transferringCnt.incrementAndGet()} files now")

      val attemptsCounter = new AtomicInteger(0)

      cloudConnector
        .download(remoteFileVersion, dest)(sendProgressUpdate)
        .doOnCancel(Task {
          logger.debug(s"Task was cancelled: download of ${dest.pathAsString}")
          downloadedFailedMeter.mark()
          transferringCnt.decrementAndGet()
        })
        .restartIf(handleRetries(attemptsCounter, dest))
        .doOnFinish(_ =>
          Task {
            sendProgressUpdate(0, 0, isFinal = true)
        })
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

          def sendProgressUpdate(uploadedBytes: Long, speed: Double, isFinal: Boolean): Unit = {
            if (isFinal) {
              logger.trace(s"Sending final progress update for $file upload")
            } else {
              logger.trace(s"Sending non-final progress update for $file upload: $uploadedBytes, $speed")
            }

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
                ),
                ignoreFailure = true
              )
              .runAsync {
                case Left(ex) => logger.debug("Could not send file upload update", ex)
              }(blockingScheduler)
          }

          withSemaphore {
            logger.debug(s"Uploading ${transferringCnt.incrementAndGet()} files now")

            val attemptsCounter = new AtomicInteger(0)

            cloudConnector
              .upload(file)(sendProgressUpdate)
              .doOnCancel(Task {
                sendProgressUpdate(0, 0, isFinal = true)
                logger.debug(s"Task was cancelled: upload of ${file.pathAsString}")
                uploadedFailedMeter.mark()
                transferringCnt.decrementAndGet()
              })
              .restartIf(handleRetries(attemptsCounter, file))
              .doOnFinish(_ =>
                Task {
                  sendProgressUpdate(0, 0, isFinal = true)
              })
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

  def removeFile(file: RemoteFile)(implicit session: ServerSession): Result[Unit] = {
    cloudConnector.removeFile(file.id).subflatMap {
      case RemoveFileResponse.Success => Right(())

      case RemoveFileResponse.PartialFailure(failures) =>
        Left(InvalidArgument(s"Could not delete remote file ${file.originalName} because: ${failures.toList.mkString(", ")}"))

      case RemoveFileResponse.FileNotFound =>
        Left(InvalidArgument(s"Could not delete remote file ${file.originalName} because it doesn't exist"))
    }
  }

  def removeFileVersion(version: RemoteFileVersion)(implicit session: ServerSession): Result[Unit] = {
    cloudConnector.removeFileVersion(version.version).subflatMap {
      case RemoveFileVersionResponse.Success => Right(())
      case RemoveFileVersionResponse.FileNotFound =>
        Left(InvalidArgument(s"Could not delete remote file version ${version.version} because it doesn't exist"))
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

  private def handleRetries(attemptsCounter: AtomicInteger, file: File)(t: Throwable): Boolean = {
    val attemptNo = attemptsCounter.get

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
          settings.uploadSameContent.withResult {
            case Right(true) => logger.debug(s"File $file not modified but 'uploadSameContent' is enabled - will upload anyway")
          }
        case true => pureResult(true)
      }
  }

  private def checkFileUpdated(file: File): Result[Boolean] = {
    dao.getFile(file).flatMap {
      case Some(dbFile) =>
        EitherT.right {
          sameFile(file, dbFile).map {
            case true =>
              logger.debug(s"File $file found in cache, is the same file -> nothing to do")
              false
            case false =>
              logger.debug(s"File $file found in cache with different content ($dbFile)")
              true
          }
        }

      case None =>
        logger.debug(s"File $file wasn't found in DB")
        pureResult(true)
    }
  }

  private[lib] def sameFile(file: File, dbFile: DbFile): Task[Boolean] = {
    val dbSeconds = dbFile.lastModified.toEpochSecond
    val mtimeSeconds = file.lastModifiedTime.getEpochSecond

    logger.debug(
      s"Is it same file ($file) - FILE ${file.lastModifiedTime}($mtimeSeconds) vs. DB ${dbFile.lastModified.toInstant}($dbSeconds)"
    )

    Task {
      dbSeconds == mtimeSeconds && dbFile.size == file.size
    }.executeOnScheduler(blockingScheduler)
  }

  private def withSemaphore[A](a: => Result[A]): Result[A] = EitherT {
    semaphore.decrement.bracketE { _ =>
      logger.debug(s"Semaphore acquired, currently transferring ${transferringCnt.get()}/$maxParallelism files")
      a.value
    } {
      case (_, Right(Right(res))) =>
        logger.trace(s"File transfer result $res, unlocking")
        semaphore.increment
          .map(_ => logger.debug(s"Semaphore released, currently transferring ${transferringCnt.get()}/$maxParallelism files"))

      case (_, Right(Left(ae))) =>
        logger.trace(s"File transfer failed, unlocking", ae)
        semaphore.increment
          .map(_ => logger.debug(s"Semaphore released, currently transferring ${transferringCnt.get()}/$maxParallelism files"))

      case (_, Left(Some(th))) =>
        logger.trace(s"File transfer failed, unlocking", th)
        semaphore.increment
          .map(_ => logger.debug(s"Semaphore released, currently transferring ${transferringCnt.get()}/$maxParallelism files"))

      case (_, Left(None)) =>
        logger.trace(s"File transfer cancelled, unlocking")
        semaphore.increment
          .map(_ => logger.debug(s"Semaphore released, currently transferring ${transferringCnt.get()}/$maxParallelism files"))
    }
  }

  override def close(): Unit = ()
}

private case class FileProgressUpdate(name: String,
                                      `type`: String,
                                      status: String,
                                      totalSize: Option[Long] = None,
                                      transferred: Option[Long] = None,
                                      speed: Option[Double] = None)
