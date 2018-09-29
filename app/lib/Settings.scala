package lib

import java.util.concurrent.atomic.AtomicReference

import io.circe.generic.extras.auto._
import io.circe.parser._
import io.circe.syntax._
import lib.App._
import lib.CirceImplicits._

class Settings(dao: Dao) {
  private val _session = new AtomicReference[Option[ServerSession]](None)

  def session: Result[Option[ServerSession]] = {
    getOrLoad("sessionId", _session, decode[ServerSession](_).getOrElse(throw new IllegalStateException("Invalid format of session in DB")))
  }

  def session(session: Option[ServerSession]): Result[Unit] = {
    _session.set(session)
    session match {
      case Some(ss: ServerSession) => set("sessionId", ss.asJson.noSpaces)
      case None => delete("sessionId")
    }
  }

  private val _initializing = new AtomicReference(true)

  def initializing: Boolean = _initializing.get

  def initializing(i: Boolean): Unit = _initializing.set(i)

  private def getOrLoad[A](key: String, ref: => AtomicReference[Option[A]], conv: String => A): Result[Option[A]] = {
    pureResult(ref.get())
      .flatMap {
        case Some(v) => pureResult(Some(v))
        case None =>
          get("sessionId").map(_.map { v =>
            val sv = conv(v)
            ref.set(Option(sv))
            sv
          })
      }
  }

  private def get(key: String): Result[Option[String]] = {
    dao.getSetting(key)
  }

  private def set(key: String, value: String): Result[Unit] = {
    dao.setSetting(key, value)
  }

  private def delete(key: String): Result[Unit] = {
    dao.deleteSetting(key)
  }
}
