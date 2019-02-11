package lib.db

import java.time.{Instant, ZoneId, ZonedDateTime}

import better.files.File
import lib.TestWithDB
import lib.server.serverapi.{RemoteFile, RemoteFileVersion}
import monix.execution.Scheduler
import monix.execution.Scheduler.Implicits.global
import scalikejdbc.{ConnectionPool, DB, _}
import utils.TestOps._

import scala.util.Random

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

  test("create and get") {
    val dao = new FilesDao(Scheduler.io())

    val file = File.newTemporaryFile()
    val time = ZonedDateTime.now(ZoneId.of("UTC+0"))
    val remoteFileVersion = RemoteFileVersion(1, Random.nextInt(), randomHash, time, time)

    val remoteFile = RemoteFile(
      42,
      "testDevice",
      file.pathAsString,
      Vector(remoteFileVersion)
    )

    dao.create(file, remoteFile).unwrappedFutureValue

    val Some(dbFile) = dao.get(file).unwrappedFutureValue
    assertResult(remoteFile)(dbFile.remoteFile)
    assertResult(remoteFileVersion.mtime.toInstant.toEpochMilli)(dbFile.lastModified.toInstant.toEpochMilli)
    assertResult(remoteFileVersion.size)(dbFile.size)
  }

//  test("listAll") {
//    DB.autoCommit { implicit session =>
//      sql"update backup_sets set last_execution = now() where id = ${bs.id}".update().apply()
//    }
//  }
}
