package lib

import java.util.concurrent.atomic.AtomicReference

import lib.App._

class Settings(dao: Dao) {
  private val _sessionId = new AtomicReference[Option[SessionId]](None)

  def sessionId: Result[Option[SessionId]] = {
    getOrLoad("sessionId", _sessionId, SessionId)
  }

  def sessionId(sessionId: Option[SessionId]): Result[Unit] = {
    _sessionId.set(sessionId)
    sessionId match {
      case Some(SessionId(value)) => set("sessionId", value)
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
