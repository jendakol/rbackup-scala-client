package lib.db

import java.time.{Duration, ZonedDateTime}

import better.files.File
import cats.data.EitherT
import com.typesafe.scalalogging.StrictLogging
import io.circe.generic.extras.auto._
import lib.App.{Result, _}
import lib.AppException
import lib.AppException.DbException
import monix.eval.Task
import monix.execution.Scheduler
import scalikejdbc.{DB, WrappedResultSet, _}
import utils.CirceImplicits._

import scala.util.control.NonFatal

//noinspection SqlNoDataSourceInspection
class BackupSetsDao(blockingScheduler: Scheduler) extends StrictLogging {

  def create(name: String): Result[BackupSet] = EitherT {
    Task {
      logger.debug(s"Saving new backup set '$name' to DB")

      val id = DB.autoCommit { implicit session =>
        sql"""INSERT INTO backup_sets (name) VALUES (${name})""".updateAndReturnGeneratedKey().apply()
      }

      val bs = BackupSet(id, name, Duration.ofHours(6), None, false)

      logger.debug(s"$bs saved")

      Right(bs): Either[AppException, BackupSet]
    }.executeOnScheduler(blockingScheduler)
      .onErrorRecover {
        case NonFatal(e) => Left(DbException("Creating backup set", e))
      }
  }

  def delete(id: Long): Result[Unit] = EitherT {
    Task {
      logger.debug(s"Deleting backup set ID '$id' from DB")

      DB.autoCommit { implicit session =>
        sql"""delete from backup_sets where id = ${id}""".update().apply()
      }

      logger.debug(s"Backup set ID $id deleted")

      Right(()): Either[AppException, Unit]
    }.executeOnScheduler(blockingScheduler)
      .onErrorRecover {
        case NonFatal(e) => Left(DbException("Deleting backup set", e))
      }
  }

  def update(bs: BackupSet): Result[Unit] = EitherT {
    Task {
      logger.debug(s"Updating backup set ID '${bs.id}' from DB")

      val freq = bs.frequency.toMinutes

      DB.autoCommit { implicit session =>
        sql"""update backup_sets set name = ${bs.name}, frequency = $freq  where id = ${bs.id}""".update().apply()
      }

      logger.debug(s"Backup set ID ${bs.id} updated")

      Right(()): Either[AppException, Unit]
    }.executeOnScheduler(blockingScheduler)
      .onErrorRecover {
        case NonFatal(e) => Left(DbException("Updating backup set", e))
      }
  }

  def listAll(): Result[List[BackupSet]] = EitherT {
    Task {
      logger.debug(s"Listing all backup sets from DB")

      val bss = DB.readOnly { implicit session =>
        sql"""select * from backup_sets""".map(BackupSet.apply).list().apply()
      }

      logger.debug(s"Retrieved backup sets: $bss")

      Right(bss): Either[AppException, List[BackupSet]]
    }.executeOnScheduler(blockingScheduler)
      .onErrorRecover {
        case NonFatal(e) => Left(DbException("Listing backup sets", e))
      }
  }

  def get(bsId: Long): Result[Option[BackupSet]] = EitherT {
    Task {
      logger.debug(s"Getting backup set from DB")

      val bs = DB.readOnly { implicit session =>
        sql"""select * from backup_sets where id=${bsId}""".map(BackupSet.apply).single().apply()
      }

      logger.debug(s"Retrieved backup set: $bs")

      Right(bs): Either[AppException, Option[BackupSet]]
    }.executeOnScheduler(blockingScheduler)
      .onErrorRecover {
        case NonFatal(e) => Left(DbException(s"Getting backup set ID $bsId", e))
      }
  }

  def updateFiles(setId: Long, files: Seq[File]): Result[Unit] = EitherT {
    Task {
      logger.debug(s"Updating backed up set files in DB")

      DB.autoCommit { implicit session =>
        sql"""merge into backup_sets_files (path, set_id) values (?, ?)"""
          .map { rs =>
            File(rs.string("path"))
          }
          .batch(files.map { file =>
            Seq(file.pathAsString, setId)
          }: _*)
          .apply()
      }

      DB.autoCommit { implicit session =>
        val currentPaths = files.map(_.pathAsString)
        logger.debug(s"Current paths: $currentPaths")

        sql"""delete from backup_sets_files where set_id = ${setId} and path not in (${currentPaths})""".update().apply()
      }

      Right(()): Either[AppException, Unit]
    }.executeOnScheduler(blockingScheduler)
      .onErrorRecover {
        case NonFatal(e) => Left(DbException("Updating backup set files", e))
      }
  }

  def listFiles(bsId: Long): Result[List[File]] = EitherT {
    Task {
      logger.debug(s"Listing files from backup set $bsId from DB")

      val files = DB.readOnly { implicit session =>
        sql"""select * from backup_sets_files where set_id=${bsId}"""
          .map { rs =>
            File(rs.string("path"))
          }
          .list()
          .apply()
      }

      logger.debug(s"Retrieved backup set files for BS $bsId: $files")

      Right(files): Either[AppException, List[File]]
    }.executeOnScheduler(blockingScheduler)
      .onErrorRecover {
        case NonFatal(e) => Left(DbException("Listing backup set files", e))
      }
  }

  def markAsExecutedNow(backupSetId: Long): Result[Unit] = EitherT {
    Task {
      logger.debug(s"Updating backed up set last execution time in DB")

      DB.autoCommit { implicit session =>
        sql"""update backup_sets set last_execution = now(), processing = false where id = ${backupSetId} """.update().apply()
      }

      Right(()): Either[AppException, Unit]
    }.executeOnScheduler(blockingScheduler)
      .onErrorRecover {
        case NonFatal(e) => Left(DbException("Updating backup set last execution time", e))
      }
  }

  def markAsProcessing(backupSetId: Long, processing: Boolean = true): Result[Unit] = EitherT {
    Task {
      logger.debug(s"Updating backed up set $backupSetId processing flag in DB to $processing")

      DB.autoCommit { implicit session =>
        sql"""update backup_sets set processing = ${processing} where id = ${backupSetId} """.update().apply()
      }

      Right(()): Either[AppException, Unit]
    }.executeOnScheduler(blockingScheduler)
      .onErrorRecover {
        case NonFatal(e) => Left(DbException("Updating backup set processing flag", e))
      }
  }

  def resetProcessingFlags(): Result[Unit] = EitherT {
    Task {
      logger.debug(s"Resetting backed up set processing flags in DB")

      DB.autoCommit { implicit session =>
        sql"""update backup_sets set processing = false """.update().apply()
      }

      Right(()): Either[AppException, Unit]
    }.executeOnScheduler(blockingScheduler)
      .onErrorRecover {
        case NonFatal(e) => Left(DbException("Resetting backup set processing flags", e))
      }
  }

  def listToExecute(): Result[List[BackupSet]] = EitherT {
    Task {
      logger.debug(s"Listing files from backup set from DB")

      val sets = DB.readOnly { implicit session =>
        sql"""select * from backup_sets where (last_execution is null OR (last_execution < DATEADD('MINUTE',-1 * frequency, now()))) AND processing = false"""
          .map(BackupSet.apply)
          .list()
          .apply()
      }

      logger.debug(s"Retrieved backup sets to be executed: $sets")

      Right(sets): Either[AppException, List[BackupSet]]
    }.executeOnScheduler(blockingScheduler)
      .onErrorRecover {
        case NonFatal(e) => Left(DbException("Listing backup set files", e))
      }
  }
}

case class BackupSet(id: Long, name: String, frequency: Duration, lastExecution: Option[ZonedDateTime], processing: Boolean)

object BackupSet {
  def apply(rs: WrappedResultSet): BackupSet = {
    import rs._

    BackupSet(
      id = long("id"),
      name = string("name"),
      frequency = Duration.ofMinutes(int("frequency")),
      lastExecution = dateTimeOpt("last_execution"),
      processing = boolean("processing")
    )
  }
}
