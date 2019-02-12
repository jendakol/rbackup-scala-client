package lib.db

import java.time.temporal.ChronoUnit
import java.time.{Instant, ZoneId, ZonedDateTime}
import java.util.concurrent.atomic.AtomicInteger

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
      List(
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
      List(remoteFileVersion)
    )

    dao.create(file, remoteFile).unwrappedFutureValue

    val Some(dbFile) = dao.get(file).unwrappedFutureValue
    assertResult(remoteFile)(dbFile.remoteFile)
    assertResult(remoteFileVersion.mtime.toInstant.toEpochMilli)(dbFile.lastModified.toInstant.toEpochMilli)
    assertResult(remoteFileVersion.size)(dbFile.size)
  }

  test("listAll") {
    val dao = new FilesDao(Scheduler.io())

    val filesC = new AtomicInteger(0)
    val versionsC = new AtomicInteger(0)

    def add(path: String): (RemoteFile, RemoteFileVersion) = {
      val time = ZonedDateTime.now(ZoneId.of("UTC+0")).truncatedTo(ChronoUnit.MILLIS)
      val remoteFileVersion = RemoteFileVersion(versionsC.incrementAndGet(), Random.nextInt(10000), randomHash, time, time)

      val remoteFile = RemoteFile(
        filesC.incrementAndGet(),
        "testDevice",
        path,
        List(remoteFileVersion)
      )

      dao.saveRemoteFile(remoteFile).unwrappedFutureValue

      (remoteFile, remoteFileVersion)
    }

    val paths = Seq(
      "/first/file.dat",
      "/first/second/file.dat",
      "/first/second/file2.dat",
      "/first/second/third/file2.dat",
      "/first/second/file3.dat",
      "c:\\dir\\file.dat",
      "c:\\file.dat",
      "c:\\dir\\dir2file.dat",
    ).sorted

    val files: Seq[DbFile] = paths.map(add).map { case (rf, rv) => DbFile(rf.originalName, rv.mtime, rv.size, rf) }

    assertResult(files)(dao.listAll().unwrappedFutureValue)
    assertResult(files.filter(_.path.startsWith("/first")))(dao.listAll(prefix = "/first").unwrappedFutureValue)

    assertResult(files.filter(_.path.startsWith("/first/second")))(dao.listAll(prefix = "/first/second").unwrappedFutureValue)
    assertResult(files.filter(_.path.startsWith("/first/second/third")))(dao.listAll(prefix = "/first/second/third").unwrappedFutureValue)
    assertResult(files.filter(_.path.startsWith("c:\\dir")))(dao.listAll(prefix = "c:\\dir").unwrappedFutureValue)
  }
}
