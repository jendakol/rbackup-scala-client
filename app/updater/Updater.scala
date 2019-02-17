package updater

import java.net.ConnectException
import java.util.concurrent.atomic.AtomicBoolean

import better.files.File
import cats.data.EitherT
import com.typesafe.scalalogging.StrictLogging
import javax.inject.{Inject, Named, Singleton}
import lib.App._
import lib.AppException.UpdateException
import lib.settings.Settings
import lib.{App, AppVersion, DeviceId}
import monix.eval.Task
import monix.execution.{Cancelable, Scheduler}
import updater.GithubConnector.Release
import utils.ConfigProperty

import scala.concurrent.duration._
import scala.util.control.NonFatal

@Singleton
class Updater @Inject()(connector: GithubConnector,
                        serviceUpdater: ServiceUpdaterExecutor,
                        settings: Settings,
                        @ConfigProperty("environment") env: String,
                        @Named("updaterCheckPeriod") checkPeriod: FiniteDuration)(implicit scheduler: Scheduler)
    extends StrictLogging {
  private val updateRunning = new AtomicBoolean(false)

  def start: Cancelable = {
    logger.info(s"Started updates checker, will check every $checkPeriod")

    scheduler.scheduleAtFixedRate(1.seconds, checkPeriod) {
      tryUpdate.value
        .runSyncUnsafe(Duration.Inf)
    }
  }

  def tryUpdate: Result[Unit] = EitherT {
    connector.checkUpdate
      .flatMap {
        case Some(rel) =>
          if (updateRunning.compareAndSet(false, true)) {
            logger.debug(s"Found update: $rel")
            updateApp(rel)
          } else pureResult(())

        case None =>
          logger.debug("Didn't find update for current version")
          pureResult(())
      }
      .recoverWith {
        case ae =>
          updateRunning.set(false)
          logger.warn("Could not download update", ae)
          EitherT.leftT[Task, Unit](ae)
      }
      .value
      .onErrorRecover {
        case e: ConnectException =>
          updateRunning.set(false)
          logger.warn("Could not download update", e)
          Left(UpdateException("Could not update the app", e))

        case e: UpdateException =>
          updateRunning.set(false)
          logger.warn("Could not download update", e)
          Left(e)

        case NonFatal(e) =>
          updateRunning.set(false)
          logger.warn("Unknown error while updating the app", e)
          Left(UpdateException("Could not update the app", e))
      }
  }

  private def updateApp(release: Release): Result[Unit] = {
    // TODO setting (if it should be updated)

    logger.info(s"Downloading update file for version ${release.tagName}")

    settings.session.map(_.map(_.deviceId).getOrElse(DeviceId("not-logged-in"))).flatMap { deviceId =>
      downloadUpdate(release).map { file =>
        val dirWithUpdate = file.unzipTo(File(s"update-${release.tagName}"))
        // the data are in subfolder, move them up
        dirWithUpdate.children.next().children.foreach { sub =>
          logger.trace(s"Moving $sub to $dirWithUpdate")
          sub.moveToDirectory(dirWithUpdate)
        }

        file.delete(true)
        logger.debug(s"Updater unzipped the update to $dirWithUpdate")

        // TODO don't do it if task is running

        val newVersion = release.appVersion.getOrElse {
          AppVersion(0, 0, 0)
        }

        logger.info(s"Starting the update from ${App.version} to $newVersion")

        serviceUpdater.executeUpdate(App.version, newVersion, env, deviceId, dirWithUpdate)
      }
    }
  }

  private def downloadUpdate(release: Release): Result[File] = {
    release.assets.find(_.name == s"rbackup-client-${release.tagName}.zip") match {
      case Some(asset) =>
        connector.download(asset)
      case None =>
        logger.warn(s"Wanted to update, but didn't found proper asset in release '${release.name}'")
        failedResult(UpdateException(s"Could not found proper asset in release '${release.name}'"))
    }
  }
}
