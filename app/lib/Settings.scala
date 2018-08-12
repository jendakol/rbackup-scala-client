package lib

import java.util.concurrent.atomic.AtomicReference

import lib.App._

class Settings(dao: Dao) {
  private val _sessionId = new AtomicReference[Option[SessionId]](None)

  def sessionId: Result[Option[SessionId]] = {
    getOrLoad("sessionId", _sessionId, SessionId)
  }

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

  def get(key: String): Result[Option[String]] = {
    dao.getSetting(key)
  }

  def set(key: String, value: String): Result[Unit] = {
    dao.setSetting(key, value)
  }

  def delete(key: String): Result[Unit] = {
    dao.deleteSetting(key)
  }
}
