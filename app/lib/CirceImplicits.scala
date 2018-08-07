package lib

import java.time.LocalDateTime
import java.util.UUID

import io.circe.generic.extras.Configuration
import io.circe.{Decoder, Encoder}

object CirceImplicits {
  implicit val configuration: Configuration = Configuration.default.withSnakeCaseConstructorNames.withSnakeCaseMemberNames

  implicit val d2: Decoder[UUID] = Decoder.decodeString.map(UUID.fromString)
  implicit val d3: Decoder[LocalDateTime] = Decoder.decodeString.map(LocalDateTime.parse)

  implicit val e2: Encoder[UUID] = Encoder.encodeString.contramap(_.toString)
  implicit val e3: Encoder[LocalDateTime] = Encoder.encodeString.contramap(_.toString)
}
