package utils

import java.time.Instant

import cats.data.EitherT
import cats.syntax.all._
import io.circe.Decoder
import lib.App.Result
import lib.AppException
import lib.AppException.{InvalidResponseException, ParsingFailure}
import monix.eval.Task
import org.http4s.client.Client
import org.http4s.{Status, Uri}
import utils.Updater.{Release, _}

class Updater(httpClient: Client[Task], uri: Uri, appVersion: AppVersion) {
  def checkUpdate: Result[Option[Release]] = {
    EitherT {
      httpClient.get(uri) { resp =>
        resp.bodyAsText.compile.toList.map(_.mkString.trim).flatMap { strBody =>
          resp.status match {
            case Status.Ok => Task.now(parse(strBody))
            case s => Task.now(Left(InvalidResponseException(s.code, strBody, "Could not download releases"): AppException))
          }
        }
      }
    }.map {
      _.flatMap { r =>
        r.appVersion.map(_ -> r).toOption
      }.sortBy(_._1)
        .reverse
        .collectFirst {
          case (v, r) if v > appVersion => r
        }
    }
  }
}

object Updater {

  import CirceImplicits._
  import io.circe.generic.extras.semiauto._
  import io.circe.parser._

  final val Repository = "jendakol/rbackup-scala-client"

  def parse(str: String): Either[ParsingFailure, Seq[Release]] = {
    decode[Seq[Release]](str).leftMap(ParsingFailure(str, _))
  }

  case class Release(tagName: String, name: String, htmlUrl: String, publishedAt: Instant, assets: Seq[Asset]) {
    val appVersion: Either[ParsingFailure, AppVersion] = AppVersion(tagName)
  }

  object Release {
    implicit val decoder: Decoder[Release] = deriveDecoder
  }

  case class Asset(name: String, size: Long, browserDownloadUrl: String)

  object Asset {
    implicit val decoder: Decoder[Asset] = deriveDecoder
  }

}
