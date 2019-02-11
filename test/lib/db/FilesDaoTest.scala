package lib.db

import java.time.{Instant, ZoneId, ZonedDateTime}

import lib.TestWithDB
import lib.server.serverapi.{RemoteFile, RemoteFileVersion}
import monix.execution.Scheduler.Implicits.global
import scalikejdbc.{ConnectionPool, DB, _}
import utils.TestOps._

class FilesDaoTest extends TestWithDB {

  // TODO add all methods

  test("getLastVersion") {
    val time = ZonedDateTime.ofInstant(Instant.ofEpochMilli(123456789), ZoneId.systemDefault())

    val rf = RemoteFile(
      42,
      "theDevice",
      "c:\\theFile\\path",
      Vector(
        RemoteFileVersion(123, 456, randomHash, time.plusMinutes(12), time.plusMinutes(122)),
        RemoteFileVersion(345, 456, randomHash, time, time.plusMinutes(1)),
        RemoteFileVersion(234, 456, randomHash, time.plusMinutes(23), time.plusMinutes(233)),
      )
    )

    assertResult(time)(FilesDao.getLastVersion(rf).created)
  }
}
