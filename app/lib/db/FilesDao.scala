package lib.db

import java.time.{ZoneId, ZonedDateTime}

import better.files.File
import cats.data
import cats.data.EitherT
import com.typesafe.scalalogging.StrictLogging
import io.circe.generic.extras.auto._
import io.circe.parser.decode
import io.circe.syntax._
import lib.App.{Result, _}
import lib.AppException
import lib.AppException.DbException
import lib.db.FilesDao._
import lib.server.serverapi.{RemoteFile, RemoteFileVersion}
import monix.eval.Task
import monix.execution.Scheduler
import scalikejdbc.{DB, _}
import utils.CirceImplicits._

import scala.util.control.NonFatal

//noinspection SqlNoDataSourceInspection
class FilesDao(blockingScheduler: Scheduler) extends StrictLogging {

  def get(file: File): Result[Option[DbFile]] = EitherT {
    Task {
      val path = file.pathAsString

      logger.debug(s"Trying to locate $file in DB")

      Right {
        DB.readOnly { implicit session =>
          sql"SELECT * FROM files WHERE path = ${path}".single().map(DbFile.apply).single().apply()
        }
      }: Either[AppException, Option[DbFile]]
    }.executeOnScheduler(blockingScheduler)
      .onErrorRecover {
        case NonFatal(e) => Left(DbException("Getting file", e))
      }
  }

  def listAll: Result[List[DbFile]] = EitherT {
    Task {
      logger.debug(s"Trying to list all files from DB")

      Right {
        DB.readOnly { implicit session =>
          sql"SELECT * FROM files".map(DbFile.apply).list().apply()
        }
      }: Either[AppException, List[DbFile]]
    }.executeOnScheduler(blockingScheduler)
      .onErrorRecover {
        case NonFatal(e) => Left(DbException("Listing files", e))
      }
  }

  def create(discoveredFile: File, remoteFile: RemoteFile): Result[Unit] = EitherT {
    Task {
      val lastVersion = getLastVersion(remoteFile)

      val fileSize = lastVersion.size
      val mtime = lastVersion.mtime
      val path = remoteFile.originalName
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
    }.executeOnScheduler(blockingScheduler)
      .onErrorRecover {
        case NonFatal(e) => Left(DbException("Creating file", e))
      }
  }

  def saveRemoteFile(remoteFile: RemoteFile): Result[Unit] = data.EitherT {
    Task {
      val lastVersion = getLastVersion(remoteFile)

      val fileSize = lastVersion.size
      val mtime = lastVersion.mtime
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
    }.executeOnScheduler(blockingScheduler)
      .onErrorRecover {
        case NonFatal(e) => Left(DbException("Creating file", e))
      }
  }

  def update(file: File, remoteFile: RemoteFile): Result[Unit] = data.EitherT {
    Task {
      val lastVersion = getLastVersion(remoteFile)

      val fileSize = lastVersion.size
      val mtime = lastVersion.mtime
      val path = remoteFile.originalName
      val remoteFileJson = remoteFile.asJson.noSpaces

      logger.debug(s"Updating $file in DB")

      DB.autoCommit { implicit session =>
        sql"merge into files key(path) values(${path}, ${mtime}, ${fileSize}, ${remoteFileJson})"
          .executeUpdate()
          .apply()
      }

      Right(()): Either[AppException, Unit]
    }.executeOnScheduler(blockingScheduler)
      .onErrorRecover {
        case NonFatal(e) => Left(DbException("Updating file", e))
      }
  }

  def delete(remoteFile: RemoteFile): Result[Unit] = EitherT {
    Task {
      val path = remoteFile.originalName

      logger.debug(s"Deleting file $path")

      DB.autoCommit { implicit session =>
        sql"DELETE FROM files WHERE path = ${path}".executeUpdate().apply()
      }

      Right(()): Either[AppException, Unit]
    }.executeOnScheduler(blockingScheduler)
      .onErrorRecover {
        case NonFatal(e) => Left(DbException("Deleting file", e))
      }
  }

  def deleteAll(): Result[Unit] = EitherT {
    Task {
      logger.debug(s"Deleting all files")

      DB.autoCommit { implicit session =>
        sql"DELETE FROM files".executeUpdate().apply()
      }

      Right(()): Either[AppException, Unit]
    }.executeOn(blockingScheduler)
      .asyncBoundary
      .onErrorRecover {
        case NonFatal(e) => Left(DbException("Deleting all files", e))
      }
  }
}

object FilesDao {
  def getLastVersion(remoteFile: RemoteFile): RemoteFileVersion = {
    remoteFile.versions
      .sortBy(_.version)
      .lastOption
      .getOrElse(throw new IllegalArgumentException("File with no versions"))
  }
}

case class DbFile(path: String, lastModified: ZonedDateTime, size: Long, remoteFile: RemoteFile)

object DbFile {
  def apply(rs: WrappedResultSet): DbFile = {
    import rs._

    DbFile(
      path = string("path"),
      lastModified = zonedDateTime("last_modified").withZoneSameInstant(ZoneId.of("UTC+0")),
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
