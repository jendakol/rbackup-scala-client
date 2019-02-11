package lib.db

import cats.data.EitherT
import com.typesafe.scalalogging.StrictLogging
import lib.App._
import lib.AppException.DbException
import lib._
import lib.db.DbUpgrader._
import monix.eval.Task
import monix.execution.Scheduler
import scalikejdbc._

import scala.collection.immutable.TreeMap

class DbUpgrader(dao: SettingsDao)(implicit sch: Scheduler) extends StrictLogging {

  def upgrade(implicit session: DBSession): Result[Unit] = {
    dao
      .get("db_version")
      .map(_.flatMap(AppVersion(_).toOption).getOrElse {
        logger.debug("Didn't found DB version, fallback to 0.1.3")
        AppVersion(0, 1, 3) // last version without this
      })
      .flatMap { currentVersion =>
        logger.debug(s"Current DB version: $currentVersion")

        if (currentVersion > App.version)
          throw new IllegalStateException(s"Cannot run with newer DB version (is $currentVersion, app ${App.version})")

        val upgrades = VersionsChanges.filterKeys(_ > currentVersion)

        upgrades
          .map {
            case (targetVersion, query) =>
              val versionStr = targetVersion.toString
              App.leaveBreadcrumb("Upgrading DB", Map("version" -> versionStr))

              logger.debug(s"Upgrading DB to version $versionStr")

              EitherT {
                Task(query.apply())
                  .map(_ => Right(()))
                  .onErrorHandle { e =>
                    Left(DbException(s"Error while upgrading the DB scheme to version $targetVersion", e): AppException)
                  }
              }.flatMap { _ =>
                dao
                  .set("db_version", versionStr)
                  .map(_ => logger.info(s"DB upgraded to version $versionStr"))
              }
          }
          .toList
          .sequentially
          .map(_ => ())
      }
  }
}

object DbUpgrader {
  private final val VersionsChanges = TreeMap[AppVersion, SQLUpdate](
    AppVersion(0, 1, 4) -> sql""" alter table settings add constraint prim_key PRIMARY KEY (key)""".update()
  )
}
