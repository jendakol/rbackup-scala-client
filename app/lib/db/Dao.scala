package lib.db

import java.time.{Duration, ZonedDateTime}
import java.util.concurrent.ExecutorService

import better.files.File
import cats.data
import cats.data.EitherT
import com.typesafe.scalalogging.StrictLogging
import io.circe.generic.extras.auto._
import io.circe.parser._
import io.circe.syntax._
import lib.App.Result
import lib.AppException
import lib.AppException.DbException
import lib.server.serverapi.RemoteFile
import monix.eval.Task
import monix.execution.Scheduler
import monix.execution.schedulers.SchedulerService
import scalikejdbc._
import utils.CirceImplicits._

import scala.util.control.NonFatal

//noinspection SqlNoDataSourceInspection
class Dao(executor: ExecutorService) extends StrictLogging {
  private val sch: SchedulerService = Scheduler(executor: ExecutorService)

  def getFile(file: File): Result[Option[DbFile]] = EitherT {
    Task {
      val path = file.pathAsString

      logger.debug(s"Trying to locate $file in DB")

      Right {
        DB.readOnly { implicit session =>
          sql"SELECT * FROM files WHERE path = ${path}".single().map(DbFile.apply).single().apply()
        }
      }: Either[AppException, Option[DbFile]]
    }.executeOn(sch)
      .asyncBoundary
      .onErrorRecover {
        case NonFatal(e) => Left(DbException("Getting file", e))
      }
  }

  def listAllFiles: Result[List[DbFile]] = EitherT {
    Task {
      logger.debug(s"Trying to list all files from DB")

      Right {
        DB.readOnly { implicit session =>
          sql"SELECT * FROM files".map(DbFile.apply).list().apply()
        }
      }: Either[AppException, List[DbFile]]
    }.executeOn(sch)
      .asyncBoundary
      .onErrorRecover {
        case NonFatal(e) => Left(DbException("Listing files", e))
      }
  }

  def newFile(discoveredFile: File, remoteFile: RemoteFile): Result[Unit] = EitherT {
    Task {
      val fileSize = discoveredFile.size
      val mtime = discoveredFile.lastModifiedTime
      val path = discoveredFile.pathAsString
      val remoteFileJson = remoteFile.asJson.noSpaces

      logger.debug(s"Saving '$discoveredFile' to DB (${remoteFile.id})")

      DB.autoCommit { implicit session =>
        sql"""
             |INSERT INTO files (path, last_modified, size, remote_file)
             |VALUES (${path}, ${mtime}, ${fileSize}, ${remoteFileJson})
             |""".stripMargin.update().apply()
      }

      logger.debug(s"$discoveredFile saved")

      Right(()): Either[AppException, Unit]
    }.executeOn(sch)
      .asyncBoundary
      .onErrorRecover {
        case NonFatal(e) => Left(DbException("Creating file", e))
      }
  }

  def saveRemoteFile(remoteFile: RemoteFile): Result[Unit] = data.EitherT {
    Task {
      val lastVersion = remoteFile.versions
        .sortBy(_.created.toEpochSecond)
        .headOption
        .getOrElse(throw new IllegalArgumentException("File with no versions"))

      val fileSize = lastVersion.size

      val mtime = lastVersion.created // TODO this it not mtime!!!
      val path = remoteFile.originalName
      val remoteFileJson = remoteFile.asJson.noSpaces

      logger.debug(s"Saving '$remoteFile' to DB")

      DB.autoCommit { implicit session =>
        sql"""
             |INSERT INTO files (path, last_modified, size, remote_file)
             |VALUES (${path}, ${mtime}, ${fileSize}, ${remoteFileJson})
             |""".stripMargin.update().apply()
      }

      logger.debug(s"$remoteFile saved")

      Right(()): Either[AppException, Unit]
    }.executeOn(sch)
      .asyncBoundary
      .onErrorRecover {
        case NonFatal(e) => Left(DbException("Creating file", e))
      }
  }

  def updateFile(file: File, remoteFile: RemoteFile): Result[Unit] = data.EitherT {
    Task {
      val fileSize = file.size
      val mtime = file.lastModifiedTime
      val remoteFileJson = remoteFile.asJson.noSpaces
      val path = file.pathAsString

      logger.debug(s"Updating $file in DB")

      DB.autoCommit { implicit session =>
        sql"merge into files key(path) values(${path}, ${mtime}, ${fileSize}, ${remoteFileJson})"
          .executeUpdate()
          .apply()
      }

      Right(()): Either[AppException, Unit]
    }.executeOn(sch)
      .asyncBoundary
      .onErrorRecover {
        case NonFatal(e) => Left(DbException("Updating file", e))
      }
  }

  def deleteFile(fileId: Long): Result[Unit] = EitherT {
    Task {
      logger.debug(s"Deleting file with ID $fileId")

      DB.autoCommit { implicit session =>
        sql"DELETE FROM files WHERE id = ${fileId}".executeUpdate().apply()
      }

      Right(()): Either[AppException, Unit]
    }.executeOn(sch)
      .asyncBoundary
      .onErrorRecover {
        case NonFatal(e) => Left(DbException("Deleting file", e))
      }
  }

  def deleteAllFiles(): Result[Unit] = EitherT {
    Task {
      logger.debug(s"Deleting all files")

      DB.autoCommit { implicit session =>
        sql"DELETE FROM files".executeUpdate().apply()
      }

      Right(()): Either[AppException, Unit]
    }.executeOn(sch)
      .asyncBoundary
      .onErrorRecover {
        case NonFatal(e) => Left(DbException("Deleting all files", e))
      }
  }

  def getSetting(key: String): Result[Option[String]] = EitherT {
    Task {
      logger.debug(s"Getting setting with key '$key'")

      Right {
        DB.readOnly { implicit session =>
          sql"SELECT value FROM settings WHERE key = ${key}".single().map(_.string("value")).single().apply()
        }
      }: Either[AppException, Option[String]]
    }.executeOn(sch)
      .asyncBoundary
      .onErrorRecover {
        case NonFatal(e) => Left(DbException("Loading setting", e))
      }
  }

  def setSetting(key: String, value: String): Result[Unit] = EitherT {
    Task {
      logger.debug(s"Setting '$key' to '$value'")

      DB.autoCommit { implicit session =>
        sql"merge into settings key(key) values (${key}, ${value})".executeUpdate().apply()
      }

      Right(()): Either[AppException, Unit]
    }.executeOn(sch)
      .asyncBoundary
      .onErrorRecover {
        case NonFatal(e) => Left(DbException("Updating setting", e))
      }
  }

  def deleteSetting(key: String): Result[Unit] = EitherT {
    Task {
      logger.debug(s"Deleting setting '$key'")

      DB.autoCommit { implicit session =>
        sql"delete from settings where key = ${key}".executeUpdate().apply()
      }

      Right(()): Either[AppException, Unit]
    }.executeOn(sch)
      .asyncBoundary
      .onErrorRecover {
        case NonFatal(e) => Left(DbException("Deleting setting", e))
      }
  }

  def createBackupSet(name: String): Result[BackupSet] = EitherT {
    Task {

      logger.debug(s"Saving new backup set '$name' to DB")

      val id = DB.autoCommit { implicit session =>
        sql"""INSERT INTO backup_sets (name) VALUES (${name})""".updateAndReturnGeneratedKey().apply()
      }

      val bs = BackupSet(id, name, Duration.ofHours(6), None, false)

      logger.debug(s"$bs saved")

      Right(bs): Either[AppException, BackupSet]
    }.executeOn(sch)
      .asyncBoundary
      .onErrorRecover {
        case NonFatal(e) => Left(DbException("Creating backup set", e))
      }
  }

  def listAllBackupSets(): Result[List[BackupSet]] = EitherT {
    Task {
      logger.debug(s"Listing all backup sets from DB")

      val bss = DB.readOnly { implicit session =>
        sql"""select * from backup_sets""".map(BackupSet.apply).list().apply()
      }

      logger.debug(s"Retrieved backup sets: $bss")

      Right(bss): Either[AppException, List[BackupSet]]
    }.executeOn(sch)
      .asyncBoundary
      .onErrorRecover {
        case NonFatal(e) => Left(DbException("Listing backup sets", e))
      }
  }

  def getBackupSet(id: Long): Result[Option[BackupSet]] = EitherT {
    Task {
      logger.debug(s"Getting backup set from DB")

      val bs = DB.readOnly { implicit session =>
        sql"""select * from backup_sets where id=${id}""".map(BackupSet.apply).single().apply()
      }

      logger.debug(s"Retrieved backup set: $bs")

      Right(bs): Either[AppException, Option[BackupSet]]
    }.executeOn(sch)
      .asyncBoundary
      .onErrorRecover {
        case NonFatal(e) => Left(DbException(s"Getting backup set ID $id", e))
      }
  }

  def updateFilesInBackupSet(setId: Long, files: Seq[File]): Result[Unit] = EitherT {
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
    }.executeOn(sch)
      .asyncBoundary
      .onErrorRecover {
        case NonFatal(e) => Left(DbException("Updating backup set files", e))
      }
  }

  def listFilesInBackupSet(id: Long): Result[List[File]] = EitherT {
    Task {
      logger.debug(s"Listing files from backup set from DB")

      val files = DB.readOnly { implicit session =>
        sql"""select * from backup_sets_files where set_id=${id}"""
          .map { rs =>
            File(rs.string("path"))
          }
          .list()
          .apply()
      }

      logger.debug(s"Retrieved backup set files: $files")

      Right(files): Either[AppException, List[File]]
    }.executeOn(sch)
      .asyncBoundary
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
    }.executeOn(sch)
      .asyncBoundary
      .onErrorRecover {
        case NonFatal(e) => Left(DbException("Updating backup set last execution time", e))
      }
  }

  def markAsProcessing(backupSetId: Long, processing: Boolean = true): Result[Unit] = EitherT {
    Task {
      logger.debug(s"Updating backed up set processing flag in DB")

      DB.autoCommit { implicit session =>
        sql"""update backup_sets set processing = ${processing} where id = ${backupSetId} """.update().apply()
      }

      Right(()): Either[AppException, Unit]
    }.executeOn(sch)
      .asyncBoundary
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
    }.executeOn(sch)
      .asyncBoundary
      .onErrorRecover {
        case NonFatal(e) => Left(DbException("Resetting backup set processing flags", e))
      }
  }

  def listBackupSetsToExecute(): Result[List[BackupSet]] = EitherT {
    Task {
      logger.debug(s"Listing files from backup set from DB")

      val sets = DB.readOnly { implicit session =>
        sql"""select * from backup_sets where last_execution is null OR (last_execution < DATEADD('MINUTE',-1 * frequency, now())) AND processing = false"""
          .map(BackupSet.apply)
          .list()
          .apply()
      }

      logger.debug(s"Retrieved backup sets to be executed: $sets")

      Right(sets): Either[AppException, List[BackupSet]]
    }.executeOn(sch)
      .asyncBoundary
      .onErrorRecover {
        case NonFatal(e) => Left(DbException("Listing backup set files", e))
      }
  }
}

case class DbFile(path: String, lastModified: ZonedDateTime, size: Long, remoteFile: RemoteFile)

object DbFile {
  def apply(rs: WrappedResultSet): DbFile = {
    import rs._

    DbFile(
      path = string("path"),
      lastModified = zonedDateTime("last_modified"),
      size = long("size"),
      remoteFile = decode[RemoteFile](string("remote_file")) match {
        case Right(v) => v
        case Left(err) =>
          println(string("remote_file"))
          throw new IllegalArgumentException("DB contains unparseable RemoteFile", err)
      }
    )
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
