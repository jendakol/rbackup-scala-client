package lib.db

import com.typesafe.scalalogging.StrictLogging
import lib.AppVersion
import scalikejdbc._

import scala.collection.immutable.TreeMap

object DbUpgrader extends StrictLogging {
  private final val VersionsChanges = TreeMap[AppVersion, SQLUpdate](
    AppVersion(0, 1, 4) -> sql""" alter table settings add constraint prim_key PRIMARY KEY (key)""".update()
  )

  def upgrade(implicit session: DBSession): Unit = {
    val currentVersion: AppVersion = {
      sql""" select value from settings where key='db_version' """
        .single()
        .map(_.string("value"))
        .single()
        .apply
        .flatMap(AppVersion(_).toOption)
        .getOrElse {
          logger.warn("Didn't found DB version, fallback to 0.1.3")
          AppVersion(0, 1, 3) // last version without this
        }
    }

    logger.debug(s"Current DB version: $currentVersion")

    val upgrades = VersionsChanges.filterKeys(_ > currentVersion)

    upgrades.foreach {
      case (version, query) =>
        logger.debug(s"Upgrading DB to version $version")
        query.apply()
        val versionStr = version.toString
        sql""" update settings set value=${versionStr} where key='db_version' """.update().apply()
        logger.debug(s"DB upraded to version $version")
    }
  }
}
