package utils

import java.time.{Duration, LocalDateTime, ZoneId, ZonedDateTime}
import java.util.UUID

import cats.syntax.either._
import io.circe.generic.extras.Configuration
import io.circe.{Decoder, Encoder}
import lib.DeviceId
import org.http4s.Uri
import updater.AppVersion

object CirceImplicits {
  implicit val configuration: Configuration = Configuration.default.withSnakeCaseConstructorNames.withSnakeCaseMemberNames

  implicit val d2: Decoder[UUID] = Decoder.decodeString.map(UUID.fromString)
  implicit val d3: Decoder[ZonedDateTime] = Decoder.decodeString.map(LocalDateTime.parse(_).atZone(ZoneId.of("UTC+0")))
  implicit val d4: Decoder[Sha256] = Decoder.decodeString.map(Sha256(_))
  implicit val d5: Decoder[Uri] = Decoder.decodeString.emap[Uri](Uri.fromString(_).leftMap(_.toString))
  implicit val d6: Decoder[DeviceId] = Decoder.decodeString.map(DeviceId)
  implicit val d7: Decoder[AppVersion] = Decoder.decodeString.emapTry(AppVersion.apply(_).toTry)

  implicit val e2: Encoder[UUID] = Encoder.encodeString.contramap(_.toString)
  implicit val e3: Encoder[ZonedDateTime] = Encoder.encodeString.contramap(_.toLocalDateTime.toString)
  implicit val e4: Encoder[Sha256] = Encoder.encodeString.contramap(_.toString)
  implicit val e5: Encoder[Uri] = Encoder.encodeString.contramap(_.toString)
  implicit val e6: Encoder[Duration] = Encoder.encodeLong.contramap(_.toHours)
  implicit val e7: Encoder[AppVersion] = Encoder.encodeString.contramap(_.toString)

}
