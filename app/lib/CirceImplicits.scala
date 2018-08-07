package lib

import io.circe.generic.extras.Configuration

object CirceImplicits {
  implicit val configuration: Configuration = Configuration.default.withSnakeCaseConstructorNames.withSnakeCaseMemberNames
}
