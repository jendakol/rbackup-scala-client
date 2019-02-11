package lib.db

import lib.App._
import lib.{App, DbScheme1_0_3, TestWithDB}
import monix.execution.Scheduler
import monix.execution.Scheduler.Implicits.global
import scalikejdbc._

import scala.concurrent.duration._

class DbUpgraderTest extends TestWithDB {
  test("upgrades DB from 0.1.3 to current") {
    DB.autoCommit { implicit session =>
      DbScheme.dropAll
      DbScheme1_0_3.create // create legacy scheme for version 1.0.3

      val upgrader = new DbUpgrader(new SettingsDao(Scheduler.global))

      upgrader.upgrade.unwrapResult
        .runSyncUnsafe(10.seconds)

      assertResult(Some(App.versionStr)) {
        sql"select value from settings where key='db_version'".map(_.string("value")).single().apply()
      }
    }
  }
}
