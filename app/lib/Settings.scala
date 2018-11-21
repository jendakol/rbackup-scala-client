package lib

import java.util.concurrent.atomic.AtomicReference

import cats.syntax.all._
import io.circe.generic.extras.auto._
import io.circe.parser._
import io.circe.syntax._
import lib.App._
import lib.CirceImplicits._
import lib.Settings._

class Settings(dao: Dao) {
  private val _session = new AtomicReference[Option[ServerSession]](None)

  def session: Result[Option[ServerSession]] = {
    getOrLoad(Names.SessionId,
              _session,
              decode[ServerSession](_).getOrElse(throw new IllegalStateException("Invalid format of session in DB")))
  }

  def session(session: Option[ServerSession]): Result[Unit] = {
    _session.set(session)
    session match {
      case Some(ss: ServerSession) => set(Names.SessionId, ss.asJson.noSpaces)
      case None => delete(Names.SessionId)
    }
  }

  private val _initializing = new AtomicReference(true)

  def initializing: Boolean = _initializing.get

  def initializing(i: Boolean): Unit = _initializing.set(i)

  private val _upload_same_content = new AtomicReference[Option[Boolean]](None)

  def uploadSameContent: Result[Boolean] = {
    getOrLoad(Names.Upload.UploadSameContent, _upload_same_content, _.toBoolean).map(_.getOrElse(false))
  }

  def uploadSameContent(upload: Boolean): Result[Unit] = {
    save[Boolean](Names.Upload.UploadSameContent, _upload_same_content, upload, _.toString)
  }

  def getList: Result[Map[String, Map[String, Setting]]] = {
    for {
      usc <- uploadSameContent
    } yield {
      Map(
        "General" ->
          Map.empty,
        "Upload" ->
          Map(
            Names.Upload.UploadSameContent -> Setting(Types.Boolean, usc.toString),
          )
      )
    }
  }

  def saveList(list: Map[String, String]): Result[Unit] = {
    def saveSetting[A](key: String, conv: String => A, save: A => Result[Unit]): Result[Unit] = {
      list.get(key).map(v => save(conv(v))).getOrElse(pureResult(()))
    }

    Seq(
      saveSetting(Names.Upload.UploadSameContent, _.toBoolean, uploadSameContent)
    ).inparallel.map(_ => ())
  }

  private def getOrLoad[A](key: String, ref: => AtomicReference[Option[A]], conv: String => A): Result[Option[A]] = {
    pureResult(ref.get())
      .flatMap {
        case Some(v) => pureResult(Some(v))
        case None =>
          get(key).map(_.map { v =>
            val sv = conv(v)
            ref.set(Option(sv))
            sv
          })
      }
  }

  private def save[A](key: String, ref: => AtomicReference[Option[A]], v: A, conv: A => String): Result[Unit] = {
    val value = conv(v)
    set(key, value) >>
      pureResult {
        ref.set(Some(v))
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

object Settings {

  object Names {
    final val SessionId = "sessionId"

    object Upload {
      final val UploadSameContent = "upload_same_content"
    }

  }

  object Types {
    final val Boolean = "boolean"
    final val Number = "number"
  }

}

case class Setting(`type`: String, value: String)
