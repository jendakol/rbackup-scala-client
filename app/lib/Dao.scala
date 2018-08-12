package lib

import java.time.ZonedDateTime
import java.util.concurrent.ExecutorService

import better.files.File
import cats.data
import cats.data.EitherT
import com.typesafe.scalalogging.StrictLogging
import io.circe.generic.extras.auto._
import io.circe.parser._
import io.circe.syntax._
import lib.App.Result
import lib.AppException.DbException
import lib.CirceImplicits._
import lib.serverapi.RemoteFile
import monix.eval.Task
import monix.execution.Scheduler
import monix.execution.schedulers.SchedulerService
import scalikejdbc._

import scala.util.control.NonFatal

class Dao(executor: ExecutorService) extends StrictLogging {
  private val sch: SchedulerService = Scheduler(executor: ExecutorService)

  def getFile(discoveredFile: File): Result[Option[DbFile]] = data.EitherT {
    Task {
      val path = discoveredFile.pathAsString

      logger.debug(s"Trying to locate $discoveredFile in DB")

      Right {
        DB.readOnly { implicit session =>
          sql"SELECT * FROM files WHERE path = ${path}".single().map(DbFile.apply).single().apply()
        }
      }
    }.executeOn(sch)
      .asyncBoundary
      .onErrorRecover {
        case NonFatal(e) => Left(DbException("Getting file", e))
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

      Right(())
    }.executeOn(sch)
      .asyncBoundary
      .onErrorRecover {
        case NonFatal(e) => Left(DbException("Creating file", e))
      }
  }

  def updateFile(fileId: Long, file: File, remoteFile: RemoteFile): Result[Unit] = EitherT {
    Task {
      val fileSize = file.size
      val mtime = file.lastModifiedTime
      val remoteFileJson = remoteFile.asJson.noSpaces

      logger.debug(s"Updating $file in DB (ID $fileId)")

      DB.autoCommit { implicit session =>
        sql"UPDATE files SET last_modified = ${mtime}, size = ${fileSize}, remote_file = ${remoteFileJson} WHERE id = ${fileId}"
          .executeUpdate()
          .apply()
      }

      Right(())
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

      Right(())
    }.executeOn(sch)
      .asyncBoundary
      .onErrorRecover {
        case NonFatal(e) => Left(DbException("Deleting file", e))
      }
  }

  def getSetting(key: String): Result[Option[String]] = EitherT {
    Task {
      logger.debug(s"Getting setting with key '$key'")

      Right {
        DB.readOnly { implicit session =>
          sql"SELECT value FROM settings WHERE key = ${key}".single().map(_.string("value")).single().apply()
        }
      }
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

      Right(())
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

      Right(())
    }.executeOn(sch)
      .asyncBoundary
      .onErrorRecover {
        case NonFatal(e) => Left(DbException("Deleting setting", e))
      }
  }
}

case class DbFile(id: Long, path: String, lastModified: ZonedDateTime, size: Long, remoteFile: RemoteFile)

object DbFile {
  def apply(rs: WrappedResultSet): DbFile = {
    import rs._

    DbFile(
      id = long("id"),
      path = string("path"),
      lastModified = dateTime("last_modified"),
      size = long("size"),
      remoteFile = decode[RemoteFile](string("remote_file"))
        .getOrElse(throw new IllegalArgumentException("DB contains unparseable RemoteFile"))
    )
  }
}
