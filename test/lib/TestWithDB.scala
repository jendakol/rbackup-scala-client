package lib

import lib.db.DbScheme
import org.scalatest.{BeforeAndAfterEach, FunSuite}
import scalikejdbc.config.DBs
import scalikejdbc.{ConnectionPool, DB}

trait TestWithDB extends FunSuite with BeforeAndAfterEach {
  override protected def beforeEach(): Unit = {
    ConnectionPool.singleton("jdbc:h2:tcp://localhost:1521/test;MODE=MySQL", "sa", "")

    DB.autoCommit { implicit session =>
      DbScheme.dropAll
      DbScheme.create
    }
  }

  override protected def afterEach(): Unit = {
    DBs.close()
  }

}
