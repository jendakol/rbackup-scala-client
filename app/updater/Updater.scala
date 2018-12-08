package updater

import better.files.File
import com.typesafe.scalalogging.StrictLogging
import javax.inject.Inject
import lib.App._
import lib.AppException.UpdateException
import monix.execution.{Cancelable, Scheduler}
import updater.GithubConnector.Release

import scala.concurrent.duration._
import scala.util.control.NonFatal

class Updater @Inject()(connector: GithubConnector, serviceUpdater: ServiceUpdater)(implicit scheduler: Scheduler) extends StrictLogging {
  def start: Cancelable = {
    logger.info("Started updates checker")

    scheduler.scheduleAtFixedRate(10.seconds, 10.seconds) {
      connector.checkUpdate
        .flatMap {
          case Some(rel) =>
            logger.debug(s"Found update: $rel")
            updateApp(rel)

          case None =>
            logger.debug("Didn't find update for current version")
            pureResult(())
        }
        .value
        .onErrorRecover {
          case e: java.net.ConnectException =>
            logger.warn("Could not check for update", e)
            Left(UpdateException("Could not update the app", e))

          case NonFatal(e) =>
            logger.warn("Unknown error while updating the app", e)
            Left(UpdateException("Could not update the app", e))
        }
        .runSyncUnsafe(Duration.Inf)
    }
  }

  private def updateApp(release: Release): Result[Unit] = {
    downloadUpdate(release).map { file =>
      val dirWithUpdate = file.unzipTo(File(s"update-${release.tagName}"))
      logger.debug(s"Updater unzipped the update to $dirWithUpdate")

      serviceUpdater.restartAndReplace(dirWithUpdate)
    }
  }

  private def downloadUpdate(release: Release): Result[File] = {
    release.assets.find(_.name == s"rbackup-client-${release.tagName}.zip") match {
      case Some(asset) => connector.download(asset)
      case None =>
        logger.warn(s"Wanted to update, but didn't found proper asset in release '${release.name}'")
        failedResult(UpdateException(s"Could not found proper asset in release '${release.name}'"))
    }
  }
}
