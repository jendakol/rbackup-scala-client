package utils

import java.time.Instant

import lib.AppException
import monix.eval.Task
import monix.execution.Scheduler.Implicits.global
import org.http4s.client.Client
import org.http4s.{Response, Status, Uri}
import org.mockito.ArgumentMatchers
import org.mockito.Mockito._
import org.scalatest.FunSuite
import org.scalatest.mockito.MockitoSugar
import utils.TestOps._
import utils.Updater.Release

import scala.io.Source

class UpdaterTest extends FunSuite with MockitoSugar {
  test("parse") {
    val Right(releases) = Updater.parse(Source.fromResource("githubReleases.json").mkString)

    assertResult(10)(releases.size)

    val release = releases(2)

    assertResult("winsw-v2.1.0")(release.tagName)
    assertResult("winsw-v2.1.0")(release.name)
    assertResult(Instant.parse("2017-04-18T23:37:09Z"))(release.publishedAt)

    val assets = release.assets

    assertResult(4)(assets.size)

    val asset = assets(3)

    assertResult("WinSW.NET4.exe")(asset.name)
    assertResult {
      "https://github.com/kohsuke/winsw/releases/download/winsw-v2.1.0/WinSW.NET4.exe"
    }(asset.browserDownloadUrl)
  }

  test("checkUpdate") {
    val cl = mock[Client[Task]]
    val updater = new Updater(cl, mock[Uri], AppVersion(2, 0, 2))

    when(cl.get(ArgumentMatchers.any[Uri])(ArgumentMatchers.any())).thenAnswer(inv => {
      inv.getArgument[Response[Task] => Task[Either[AppException, Seq[Release]]]](1).apply {
        Response(status = Status.Ok, body = fs2.Stream.emits(Source.fromResource("githubReleases.json").map(_.toByte).toSeq).covary[Task])
      }
    })

    val Some(release) = updater.checkUpdate.unwrappedFutureValue

    assertResult("winsw-v2.1.2")(release.tagName) // next is 2.0.3, newest is 2.1.2
    assertResult("winsw-v2.1.2")(release.name)
    assertResult(Right(AppVersion(2, 1, 2)))(release.appVersion)
    assertResult(Instant.parse("2017-07-08T16:29:51Z"))(release.publishedAt)
  }
}
