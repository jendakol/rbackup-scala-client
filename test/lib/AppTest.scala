package lib

import io.circe.Json
import io.circe.parser._
import lib.db.DbScheme
import org.scalatest.FunSuite
import org.scalatest.concurrent.Eventually
import org.scalatest.time.{Seconds, Span}
import org.scalatestplus.play.guice.GuiceOneServerPerSuite
import play.api.Application
import play.api.inject.guice.GuiceApplicationBuilder
import scalaj.http.Http
import scalikejdbc.{ConnectionPool, DB}

import scala.io.Source

class AppTest extends FunSuite with Eventually with GuiceOneServerPerSuite {

  private val rbackupIp: String = {
    val ip = Option(System.getenv("RBACKUP_IP")).getOrElse("localhost")

    if (ip == "0.0.0.0") "localhost" else ip
  }

  test("app starts") {
    eventually(timeout(Span(10, Seconds))) {
      Source.fromURL(s"http://$rbackupIp:$port/").mkString // it should only not fail
    }
  }

  override def fakeApplication(): Application = {
    ConnectionPool.singleton(s"jdbc:h2:tcp://$rbackupIp:1521/test;MODE=MySQL", "sa", "")

    DB.autoCommit { implicit session =>
      DbScheme.dropAll
    }

    new GuiceApplicationBuilder()
      .configure("play.filters.hosts.allowed" -> Seq(s"localhost:$port"))
      .build()
  }

  private def sendCommand(name: String, data: Json): Json = {
    val resp = Http(s"http://localhost:$port/ajax-api").postData(s"""{"name": "$name", "data": $data}""").asString

    if (!resp.is2xx) fail(resp.body)

    parse(resp.body).toTry.fold(throw _, identity)
  }

}
