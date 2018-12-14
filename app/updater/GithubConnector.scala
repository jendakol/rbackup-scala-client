package updater

import java.io.IOException
import java.time.Instant

import better.files.File
import cats.data.EitherT
import cats.effect.Effect
import cats.syntax.all._
import com.typesafe.scalalogging.StrictLogging
import io.circe.Decoder
import lib.App._
import lib.AppException
import lib.AppException._
import monix.eval.Task
import monix.execution.Scheduler
import org.http4s.client.Client
import org.http4s.headers.`Content-Length`
import org.http4s.{Response, Status, Uri}
import updater.GithubConnector._

import scala.concurrent.ExecutionContext

class GithubConnector(httpClient: Client[Task], uri: Uri, appVersion: AppVersion, blockingScheduler: Scheduler)(implicit F: Effect[Task])
    extends StrictLogging {
  def checkUpdate: Result[Option[Release]] = {
    EitherT {
      logger.debug(s"Checking update at $uri")

      httpClient.get(uri) { resp =>
        resp.bodyAsText.compile.toList.map(_.mkString.trim).flatMap { strBody =>
          resp.status match {
            case Status.Ok =>
              logger.debug(s"Updater check response:\n$strBody")
              Task.now(parse(strBody))
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
      logger.debug(s"Downloading update from ${asset.browserDownloadUrl}")

      httpClient
        .get(asset.browserDownloadUrl) { resp =>
          resp.status match {
            case Status.Ok =>
              `Content-Length`.from(resp.headers) match {
                case Some(clh) =>
                  if (clh.length == asset.size) {
                    download(asset, resp)
                  } else {
                    resp.bodyAsText.compile.toList.map(_.mkString).map { body =>
                      logger.debug(s"Content-Length header doesn't match asset size - ${clh.length} != ${asset.size}\n$body")
                      Left(UpdateException("Content-Length header doesn't match asset size"))
                    }
                  }

                case None =>
                  resp.bodyAsText.compile.toList.map(_.mkString).map { body =>
                    logger.debug(s"Missing Content-Length header, expected ${asset.size}\n$body")
                    Left(UpdateException("Missing Content-Length header"))
                  }
              }

            case s =>
              resp.bodyAsText.compile.toList.map(_.mkString).map { body =>
                logger.debug(s"Missing Content-Length header, expected ${asset.size}\n$body")
                Left(InvalidResponseException(s.code, body, "Update failure"))
              }
          }

        }
    }
  }

  private def download(asset: Asset, resp: Response[Task]): Task[Either[AppException, File]] = {
    val file = File.temporaryFile("rbackup", s"update-${asset.name}").get
    implicit val ec: ExecutionContext = blockingScheduler

    resp.body
      .through(fs2.io.file.writeAllAsync(file.path))
      .compile
      .drain
      .map { _ =>
        if (file.size == asset.size) {
          logger.debug(s"Update downloaded to $file")
          Right(file): Either[AppException, File]
        } else {
          logger.debug(s"Size of downloaded file mismatch - ${file.size} != ${asset.size}")
          Left(UpdateException("Size of downloaded file mismatch"))
        }
      }
      .onErrorRecover {
        case e: IOException => Left(FileException("Could not download update", e))
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
