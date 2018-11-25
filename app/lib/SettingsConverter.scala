package lib

import java.time.Instant

import io.circe.parser._
import io.circe.syntax._
import io.circe.{Decoder, Encoder}

trait SettingsConverter[A] {
  def fromString(s: String): A

  def toString(a: A): String

  def uiTypeName: String
}

object SettingsConverter {
  implicit val booleanConverter: SettingsConverter[Boolean] = new SettingsConverter[Boolean] {
    override def fromString(s: String): Boolean = s.toBoolean

    override def toString(a: Boolean): String = a.toString

    override val uiTypeName: String = "boolean"
  }

  implicit val instantConverter: SettingsConverter[Instant] = new SettingsConverter[Instant] {
    override def fromString(s: String): Instant = Instant.parse(s)

    override def toString(i: Instant): String = i.toString

    override val uiTypeName: String = "datetime"
  }

  implicit def jsonConverter[A: Encoder: Decoder]: SettingsConverter[A] = new SettingsConverter[A] {
    override def fromString(s: String): A = decode[A](s).getOrElse(throw new IllegalStateException("Invalid format value in DB"))

    override def toString(a: A): String = a.asJson.noSpaces

    override def uiTypeName: String = throw new UnsupportedOperationException
  }

  //  implicit def optionConverter[A: SettingsConverter]: SettingsConverter[Option[A]] = new SettingsConverter[Option[A]] {
  //    private val inner = implicitly[SettingsConverter[A]]
  //
  //    override def fromString(s: String): Option[A] = {
  //      if (StringUtils.isNotEmpty(s)) Option(inner.fromString(s)) else None
  //    }
  //
  //    override def toString(a: Option[A]): String = {
  //      a.map(inner.toString).getOrElse("")
  //    }
  //  }
}
