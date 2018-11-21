package lib

import com.typesafe.scalalogging.StrictLogging
import controllers.WsApiController
import javax.inject.Inject
import lib.App.{parseUnsafe, _}
import lib.AppException.MultipleFailuresException
import monix.execution.{Cancelable, Scheduler}

import scala.concurrent.duration._

class BackupSetsExecutor @Inject()(dao: Dao,
                                   filesHandler: FilesHandler,
                                   tasksManager: TasksManager,
                                   wsApiController: WsApiController,
                                   settings: Settings)(implicit scheduler: Scheduler)
    extends StrictLogging {

  def start: Cancelable = {
    logger.info("Started execution of backup sets")

    scheduler.scheduleAtFixedRate(10.seconds, 1.minute) {
      logger.debug("Executing waiting backup sets")
      settings.session.map {
        case Some(sid) => executeWaitingBackupSets()(sid)
        case None => logger.info("Could not process backup sets - missing server session")
      }
    }
  }

  private def executeWaitingBackupSets()(implicit session: ServerSession): Unit = {
    (for {
      sets <- dao.listBackupSetsToExecute()
      _ <- sets.map(execute).sequentially
    } yield {
      sets
    }).runAsync {
      case Right(sets) => logger.info(s"All backup sets were processes successfully (${sets.map(_.name)})")
      case Left(ex: MultipleFailuresException) => logger.warn(s"Execution of backup sets failed:\n${ex.causes.mkString("\n")}", ex)
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
          "backupSetDetailsUpdate",
          parseUnsafe(
            s"""{ "id": ${currentBs.id}, "type": "processing", "processing": ${currentBs.processing}, "last_execution": "$lastTime", "next_execution": "$nextTime"}""")
        )

      case None => throw new IllegalStateException("Must NOT happen")
    }

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
            parseUnsafe(s"""{ "success": true, "name": "${bs.name}"}""")
          )
        } yield {}).recoverWith {
          case ex: MultipleFailuresException =>
            logger.warn(s"Execution of backup set failed:\n${ex.causes.mkString("\n")}", ex)
            wsApiController.send(
              "backupFinish",
              parseUnsafe(s"""{ "success": false, "name": "${bs.name}", "reason": "Multiple failures"}""")
            )

          case e =>
            logger.debug(s"Backup set execution failed ($bs)", e)
            wsApiController.send(
              "backupFinish",
              parseUnsafe(s"""{ "success": false, "name": "${bs.name}", "reason": "Multiple failures"}""")
            )
        }
      }

  }
}
