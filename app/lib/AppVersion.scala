package lib

import lib.AppException.ParsingFailure
import lib.AppVersion._

case class AppVersion(major: Int, minor: Int, build: Int, suffix: Option[String] = None) {
  def >(other: AppVersion): Boolean = {
    ordering.gt(this, other)
  }

  def <(other: AppVersion): Boolean = {
    ordering.lt(this, other)
  }

  override val toString: String = s"$major.$minor.$build${suffix.map("-" + _).getOrElse("")}"
}

object AppVersion {
  private val VersionPattern = "[a-zA-Z-_]*(\\d+)\\.(\\d+)\\.(\\d+)(\\-\\w+)?.*".r

  def apply(tagName: String): Either[ParsingFailure, AppVersion] = {
    tagName match {
      case VersionPattern(major, minor, build, suffix) =>
        Right {
          AppVersion(major.toInt, minor.toInt, build.toInt, Option(suffix).map(_.drop(1)))
        }
      case _ => Left(ParsingFailure(tagName))
    }
  }

  implicit val ordering: Ordering[AppVersion] = (x: AppVersion, y: AppVersion) => {
    def comp(x: Int, y: Int): Option[Int] = {
      if (x > y) Some(1)
      else if (x < y) Some(-1)
      else None
    }

    comp(x.major, y.major) orElse
      comp(x.minor, y.minor) orElse
      comp(x.build, y.build) getOrElse {
      (x.suffix, y.suffix) match {
        case (Some(l), Some(r)) => l.compareToIgnoreCase(r)
        case (Some(_), None) => -1
        case (None, Some(_)) => 1
        case (None, None) => 0
      }
    }
  }
}
