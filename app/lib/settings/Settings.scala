package lib.settings

import java.time.Instant
import java.util.concurrent.atomic.AtomicReference

import cats.syntax.all._
import com.typesafe.scalalogging.StrictLogging
import io.circe.generic.extras.auto._
import lib.App._
import lib.ServerSession
import lib.db.Dao
import lib.settings.Settings._
import org.apache.commons.lang3.StringUtils
import utils.CirceImplicits._

import scala.util.control.NonFatal

class Settings(dao: Dao) extends StrictLogging {
  private val _initializing = new AtomicReference(true)

  def initializing: Boolean = _initializing.get

  def initializing(i: Boolean): Unit = _initializing.set(i)

  private val _session = new CachedSetting[ServerSession](Names.SessionId)

  def session: Result[Option[ServerSession]] = _session.get

  def session(session: Option[ServerSession]): Result[Unit] = _session.set(session)

  private val _uploadSameContent = new CachedSetting[Boolean](Names.Upload.UploadSameContent)

  def uploadSameContent: Result[Boolean] = _uploadSameContent.get.map(_.getOrElse(false))

  def uploadSameContent(upload: Boolean): Result[Unit] = _uploadSameContent.set(Some(upload))

  private val _backupSetsSuspended = new CachedSetting[Instant](Names.BackupSets.SuspendedUntil)

  def suspendedBackupSets: Result[Option[Instant]] = _backupSetsSuspended.get

  def suspendedBackupSets(until: Option[Instant]): Result[Unit] = _backupSetsSuspended.set(until)

  def getList: Result[Map[String, Map[String, Setting]]] = {
    for {
      usc <- uploadSameContent
      backsusp <- suspendedBackupSets
    } yield {
      Map(
        "General" ->
          Map.empty,
        "Upload" ->
          Map(
            _uploadSameContent.toUiSetting
          ),
        "BackupSets" ->
          Map(
            _backupSetsSuspended.toUiSetting
          )
      )
    }
  }

  def saveList(list: Map[String, String]): Result[Unit] = {
    def saveSetting[A](cs: CachedSetting[A]): Result[Unit] = {
      list.get(cs.key).map(cs.set).getOrElse(pureResult(())) // TODO should it be deleted?
    }

    Seq(
      saveSetting(_uploadSameContent),
      saveSetting(_backupSetsSuspended),
    ).inparallel.map(_ => ())
  }

  private def getOrLoad[A](key: String, ref: => AtomicReference[Option[A]], conv: String => A): Result[Option[A]] = {
    pureResult(ref.get())
      .flatMap {
        case Some(v) => pureResult(Some(v))
        case None =>
          get(key).map(_.flatMap { v =>
            try {
              val sv = conv(v)
              ref.set(Option(sv))
              Some(sv)
            } catch {
              case NonFatal(e) =>
                logger.info(s"Could not load the '$key' setting, defaulting to None", e)
                ref.set(None)
                None
            }
          })
      }
  }

  private def save[A](key: String, ref: => AtomicReference[Option[A]], v: A, conv: A => String): Result[Unit] = {
    val value = conv(v)
    logger.debug(s"Updating setting value: $key -> $v")
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

  private class CachedSetting[A: SettingsConverter](val key: String) {
    private val cachedValue = new AtomicReference[Option[A]](None)
    private val converter = implicitly[SettingsConverter[A]]

    def get: Result[Option[A]] = {
      getOrLoad(key, cachedValue, converter.fromString)
    }

    def set(value: Option[A]): Result[Unit] = {
      value match {
        case Some(v) => save[A](key, cachedValue, v, converter.toString)
        case None => delete(key).map(_ => cachedValue.set(None))
      }
    }

    def set(value: String): Result[Unit] = {
      if (StringUtils.isNotEmpty(value))
        set(Some(converter.fromString(value)))
      else
        set(None)
    }

    def toUiSetting: (String, Setting) = {
      key -> Setting(
        `type` = converter.uiTypeName,
        value = cachedValue.get().map(converter.toString).getOrElse("")
      )
    }
  }

}

object Settings {

  object Names {
    final val SessionId = "sessionId"

    object Upload {
      final val UploadSameContent = "upload_same_content"
    }

    object BackupSets {
      final val SuspendedUntil = "backupsets_suspended_until"
    }

  }

  object Types {
    final val Boolean = "boolean"
    final val Number = "number"
    final val DateTime = "datetime"
  }

}

case class Setting(`type`: String, value: String)
