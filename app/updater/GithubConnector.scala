package updater

import java.io.IOException
import java.time.Instant

import better.files.File
import cats.data.EitherT
import cats.effect.Effect
import cats.syntax.all._
import io.circe.Decoder
import lib.App._
import lib.AppException
import lib.AppException._
import monix.eval.Task
import monix.execution.Scheduler
import org.http4s.client.Client
import org.http4s.{Status, Uri}
import updater.GithubConnector._

import scala.concurrent.ExecutionContext

class GithubConnector(httpClient: Client[Task], uri: Uri, appVersion: AppVersion, blockingScheduler: Scheduler)(implicit F: Effect[Task]) {
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

  def download(asset: Asset): Result[File] = {
    EitherT {
      httpClient
        .get(asset.browserDownloadUrl) { resp =>
          val file = File.temporaryFile("rbackup", s"update-${asset.name}").get
          implicit val ec: ExecutionContext = blockingScheduler

          resp.body
            .through(fs2.io.writeOutputStreamAsync(Task {
              file.newOutputStream
            }.executeOnScheduler(blockingScheduler)))
            .compile
            .drain
            .map(_ => Right(file): Either[AppException, File])
            .onErrorRecover {
              case e: IOException => Left(FileException("Could not download update", e))
            }
        }

    }
  }
}

object GithubConnector {

  import io.circe.generic.extras.semiauto._
  import io.circe.parser._
  import utils.CirceImplicits._

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
