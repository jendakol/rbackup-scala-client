package lib

import java.time.Instant

import cats.syntax.all._
import com.typesafe.scalalogging.StrictLogging
import controllers.WsApiController
import io.sentry.Sentry
import javax.inject.{Inject, Named}
import lib.App.{parseUnsafe, _}
import lib.AppException.MultipleFailuresException
import lib.db.{BackupSet, Dao}
import lib.settings.Settings
import monix.execution.{Cancelable, Scheduler}

import scala.concurrent.duration._

class BackupSetsExecutor @Inject()(dao: Dao,
                                   filesHandler: FilesHandler,
                                   tasksManager: TasksManager,
                                   wsApiController: WsApiController,
                                   @Named("blocking") blockingScheduler: Scheduler,
                                   settings: Settings)(implicit scheduler: Scheduler)
    extends StrictLogging {

  def start: Cancelable = {
    logger.info("Started execution of backup sets")

    scheduler.scheduleAtFixedRate(10.seconds, 1.minute) {
      (for {
        session <- settings.session
        suspended <- executionsSuspended
      } yield {
        session match {
          case Some(sid) =>
            if (!suspended) executeWaitingBackupSets()(sid)
            else {
              logger.info("Execution of backup sets is suspended")
            }

          case None => logger.info("Could not process backup sets - missing server session")
        }
      }).value
        .runSyncUnsafe(Duration.Inf)(blockingScheduler, implicitly)
    }
  }

  private def executeWaitingBackupSets()(implicit session: ServerSession): Unit = {
    logger.debug("Executing waiting backup sets")

    (for {
      sets <- dao.listBackupSetsToExecute()
      _ = logger.debug(s"Backup sets to be executed: ${sets.mkString("\n")}")
      _ <- sets.map(execute).sequentially
    } yield {
      sets
    }).runAsync {
      case Right(sets) =>
        if (sets.nonEmpty)
          logger.info(s"All backup sets were processes successfully (${sets.map(_.name).mkString(", ")})")

      case Left(ex: MultipleFailuresException) =>
        ex.causes.foreach(Sentry.capture)
        logger.info(s"Execution of backup sets failed:\n${ex.causes.mkString("\n")}", ex)

      case Left(ex: AppException) => logger.warn("Execution of backup sets failed", ex)
      case Left(ex) => logger.error("Execution of backup sets failed", ex)
    }
  }

  def execute(bs: BackupSet)(implicit session: ServerSession): Result[Unit] = {
    def updateUi(): Result[Unit] = dao.getBackupSet(bs.id).flatMap {
      case Some(currentBs) =>
        val lastTime = currentBs.lastExecution.map(DateTimeFormatter.format).getOrElse("never")
        val nextTime = currentBs.lastExecution.map(_.plus(currentBs.frequency)).map(DateTimeFormatter.format).getOrElse("soon")

        wsApiController.send(
          `type` = "backupSetDetailsUpdate",
          data = parseUnsafe(
            s"""{ "id": ${currentBs.id}, "type": "processing", "processing": ${currentBs.processing}, "last_execution": "$lastTime", "next_execution": "$nextTime"}"""
          ),
          ignoreFailure = true
        )

      case None => throw new IllegalStateException("Must NOT happen")
    }

    logger.debug(s"Executing $bs")

    tasksManager
      .start(RunningTask.BackupSetUpload(bs.name)) {
        (for {
          _ <- dao.markAsProcessing(bs.id)
          _ <- updateUi()
          files <- dao.listFilesInBackupSet(bs.id)
          _ <- files.map(filesHandler.upload(_)).inparallel // TODO
          _ <- dao.markAsExecutedNow(bs.id)
          _ <- updateUi()
          _ <- wsApiController.send(
            "backupFinish",
            parseUnsafe(s"""{ "success": true, "name": "${bs.name}"}"""),
            ignoreFailure = true
          )
        } yield {}).recoverWith {
          case ex: MultipleFailuresException =>
            logger.warn(s"Execution of backup set failed:\n${ex.causes.mkString("\n")}", ex)
            dao.markAsProcessing(bs.id, processing = false) >>
              updateUi() >>
              wsApiController.send(
                `type` = "backupFinish",
                data = parseUnsafe(s"""{ "success": false, "name": "${bs.name}", "reason": "Multiple failures"}"""),
                ignoreFailure = true
              )

          case e =>
            logger.debug(s"Backup set execution failed ($bs)", e)
            dao.markAsProcessing(bs.id, processing = false) >>
              updateUi() >>
              wsApiController.send(
                `type` = "backupFinish",
                data = parseUnsafe(s"""{ "success": false, "name": "${bs.name}", "reason": "Multiple failures"}"""),
                ignoreFailure = true
              )
        }
      }
      .doOnCancel {
        dao.markAsProcessing(bs.id, processing = false) >>
          updateUi() >>
          pureResult(logger.debug(s"Backup set cancelled ($bs)"))
      }
  }

  private def executionsSuspended: Result[Boolean] = {
    settings.suspendedBackupSets.map {
      case Some(until) =>
        logger.debug(s"Backup sets suspended until $until")
        until isAfter Instant.now()
      case None => false
    }
  }
}
