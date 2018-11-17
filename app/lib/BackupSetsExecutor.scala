package lib

import com.typesafe.scalalogging.StrictLogging
import javax.inject.Inject
import lib.App._
import monix.execution.Scheduler

import scala.concurrent.duration._

class BackupSetsExecutor @Inject()(dao: Dao, filesHandler: FilesHandler, tasksManager: TasksManager, settings: Settings)(
    implicit scheduler: Scheduler)
    extends StrictLogging {
  scheduler.scheduleAtFixedRate(10.seconds, 1.minute) {
    logger.debug("Executing waiting backup sets")
    settings.session.map {
      case Some(sid) => processWaitingBackupSets()(sid)
      case None => logger.info("Could not process backup sets - missing server session")
    }
  }

  private def processWaitingBackupSets()(implicit session: ServerSession): Unit = {
    (for {
      sets <- dao.listBackupSetsToExecute()
      allResult <- sets.map(process).sequentially
    } yield {
      (sets zip allResult).toMap
    }).runAsync {
      case Right(setsWithResults) =>
        val (failures, _) = setsWithResults.collectPartition {
          case (_, Right(_)) => Right(())
          case (set, Left(ex)) => Left(set -> ex)
        }

        if (failures.nonEmpty) {
          failures.foreach {
            case (set, ex) =>
              logger.warn(s"Backup set '${set.name}' failed", ex)
          }
        } else {
          logger.info(s"All backup sets were processes successfully (${setsWithResults.keys.map(_.name)})")
        }

      case Left(ex: AppException) => logger.warn("Execution of backup sets failed", ex)
      case Left(ex) => logger.error("Execution of backup sets failed", ex)
    }
  }

  private def process(bs: BackupSet)(implicit session: ServerSession): Result[Unit] = {
    tasksManager.start(RunningTask.BackupSetUpload(bs.name)) {
      for {
        _ <- dao.markAsProcessing(bs.id)
        files <- dao.listFilesInBackupSet(bs.id)
        _ <- files.map(filesHandler.uploadNow(_)).inparallel
        _ <- dao.markAsExecutedNow(bs.id)
      } yield {}
    }
  }
}
