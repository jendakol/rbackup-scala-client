package lib.db

import cats.data.EitherT
import com.typesafe.scalalogging.StrictLogging
import io.circe.generic.extras.auto._
import lib.App.{Result, _}
import lib.AppException
import lib.AppException.DbException
import monix.eval.Task
import monix.execution.Scheduler
import scalikejdbc.{DB, _}
import utils.CirceImplicits._

import scala.util.control.NonFatal

//noinspection SqlNoDataSourceInspection
class SettingsDao(blockingScheduler: Scheduler) extends StrictLogging {

  def get(key: String): Result[Option[String]] = EitherT {
    Task {
      logger.debug(s"Getting setting with key '$key'")

      Right {
        DB.readOnly { implicit session =>
          sql"SELECT value FROM settings WHERE key = ${key}".single().map(_.string("value")).single().apply()
        }
      }: Either[AppException, Option[String]]
    }.executeOnScheduler(blockingScheduler)
      .onErrorRecover {
        case NonFatal(e) => Left(DbException(s"Loading setting '$key'", e))
      }
  }

  def set(key: String, value: String): Result[Unit] = EitherT {
    Task {
      logger.debug(s"Setting '$key' to '$value'")

      DB.autoCommit { implicit session =>
        sql"merge into settings key(key) values (${key}, ${value})".executeUpdate().apply()
      }

      Right(()): Either[AppException, Unit]
    }.executeOnScheduler(blockingScheduler)
      .onErrorRecover {
        case NonFatal(e) => Left(DbException("Updating setting", e))
      }
  }

  def delete(key: String): Result[Unit] = EitherT {
    Task {
      logger.debug(s"Deleting setting '$key'")

      DB.autoCommit { implicit session =>
        sql"delete from settings where key = ${key}".executeUpdate().apply()
      }

      Right(()): Either[AppException, Unit]
    }.executeOnScheduler(blockingScheduler)
      .onErrorRecover {
        case NonFatal(e) => Left(DbException("Deleting setting", e))
      }
  }
}
