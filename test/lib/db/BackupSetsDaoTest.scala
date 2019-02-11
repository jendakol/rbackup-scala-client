package lib.db

import java.time.Duration

import better.files.File
import lib.TestWithDB
import monix.execution.Scheduler
import monix.execution.Scheduler.Implicits.global
import scalikejdbc.{ConnectionPool, DB, _}
import utils.TestOps._

class BackupSetsDaoTest extends TestWithDB {

  // TODO add all methods

  test("insert and list backup set") {
    val dao = new BackupSetsDao(Scheduler.io())

    assertResult(List.empty)(dao.listAll().unwrappedFutureValue)
    val bs = dao.create("ahoj").unwrappedFutureValue
    assertResult("ahoj")(bs.name)
    assertResult(None)(bs.lastExecution)
    assertResult(false)(bs.processing)
    assertResult(List(bs))(dao.listAll().unwrappedFutureValue)
  }

  test("update backup set files") {
    val dao = new BackupSetsDao(Scheduler.io())

    val bs = dao.create("ahoj").unwrappedFutureValue

    val files = List(File("/aaTestDir/aaTestFile"), File("/aaTestDir/A77_32122.jpg"))
    dao.updateFiles(bs.id, files).unwrappedFutureValue
    assertResult(files.toSet)(dao.listFiles(bs.id).unwrappedFutureValue.toSet)

    val files2 = List(File("/aaTestDir/aaTestFile"), File("/aaTestDir/A77_32123.jpg"))
    dao.updateFiles(bs.id, files2).unwrappedFutureValue
    assertResult(files2.toSet)(dao.listFiles(bs.id).unwrappedFutureValue.toSet)
  }

  test("markAsExecutedNow") {
    val dao = new BackupSetsDao(Scheduler.io())

    dao.create("ahoj").unwrappedFutureValue
    val List(bs) = dao.listAll().unwrappedFutureValue
    assertResult(None)(bs.lastExecution)
    assertResult(false)(bs.processing)

    DB.autoCommit { implicit session =>
      sql"update backup_sets set processing = true where id = ${bs.id}".update().apply()
    }

    dao.markAsExecutedNow(bs.id).unwrappedFutureValue

    val List(bsu) = dao.listAll().unwrappedFutureValue
    assert(bsu.lastExecution.nonEmpty)
  }

  test("markAsProcessing") {
    val dao = new BackupSetsDao(Scheduler.io())

    dao.create("ahoj").unwrappedFutureValue
    val List(bs) = dao.listAll().unwrappedFutureValue
    assertResult(None)(bs.lastExecution)
    assertResult(false)(bs.processing)

    dao.markAsProcessing(bs.id).unwrappedFutureValue

    assertResult(true)(dao.listAll().unwrappedFutureValue.head.processing)
  }

  test("listBackupSetsToExecute") {
    val dao = new BackupSetsDao(Scheduler.io())

    dao.create("ahoj").unwrappedFutureValue
    dao.create("ahoj2").unwrappedFutureValue
    dao.create("ahoj3").unwrappedFutureValue
    dao.create("ahoj4").unwrappedFutureValue
    dao.create("ahoj5").unwrappedFutureValue
    val List(bs, bs2, bs3, bs4, bs5) = dao.listAll().unwrappedFutureValue

    DB.autoCommit { implicit session =>
      sql"update backup_sets set last_execution = now() where id = ${bs.id}".update().apply()
      sql"update backup_sets set frequency = 120, last_execution = DATEADD('HOUR', -3, now()) where id = ${bs2.id}".update().apply()
      sql"update backup_sets set frequency = 400, last_execution = DATEADD('HOUR', -3, now()) where id = ${bs3.id}".update().apply()
      sql"update backup_sets set frequency = 400, last_execution = DATEADD('HOUR', -7, now()) where id = ${bs4.id}".update().apply()
      sql"update backup_sets set processing = true, frequency = 120, last_execution = DATEADD('HOUR', -7, now()) where id = ${bs5.id}"
        .update()
        .apply()
    }

    assertResult(
      List(
        bs2.copy(frequency = Duration.ofMinutes(120)),
        bs4.copy(frequency = Duration.ofMinutes(400))
      ))(dao.listToExecute().unwrappedFutureValue.map(_.copy(lastExecution = None)))
  }

  test("markAsProcessing -> listBackupSetsToExecute") {
    val dao = new BackupSetsDao(Scheduler.io())

    dao.create("ahoj").unwrappedFutureValue
    dao.create("ahoj2").unwrappedFutureValue
    dao.create("ahoj3").unwrappedFutureValue
    dao.create("ahoj4").unwrappedFutureValue
    dao.create("ahoj5").unwrappedFutureValue

    val List(bs, bs2, bs3, bs4, bs5) = dao.listAll().unwrappedFutureValue

    dao.markAsProcessing(bs.id).unwrappedFutureValue
    dao.markAsProcessing(bs3.id).unwrappedFutureValue
    dao.markAsProcessing(bs5.id).unwrappedFutureValue

    assertResult(List(bs2, bs4).map(_.id)) {
      dao.listToExecute().unwrappedFutureValue.map(_.id)
    }
  }
}
