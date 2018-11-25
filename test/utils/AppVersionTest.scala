package utils

import lib.AppException.ParsingFailure
import org.scalatest.FunSuite

class AppVersionTest extends FunSuite {
  test("parse from tag name") {
    assertResult(Right(AppVersion(1, 0, 0)))(AppVersion("1.0.0"))
    assertResult(Right(AppVersion(1, 0, 0, Some("beta"))))(AppVersion("1.0.0-beta"))
    assertResult(Left(ParsingFailure("1-0.0-beta")))(AppVersion("1-0.0-beta"))
    assertResult(Left(ParsingFailure("1-0.0.0-beta")))(AppVersion("1-0.0.0-beta"))
    assertResult(Right(AppVersion(2, 1, 0, None)))(AppVersion("winsw-v2.1.0"))
    assertResult(Left(ParsingFailure("winsw1-v2.1.0")))(AppVersion("winsw1-v2.1.0"))
  }

  test("compare") {
    assert(AppVersion(1, 0, 0) > AppVersion(0, 5, 5))
    assert(AppVersion(1, 5, 0) > AppVersion(1, 4, 5))
    assert(AppVersion(1, 5, 5) > AppVersion(1, 4, 4))
    assert(AppVersion(1, 0, 0) < AppVersion(1, 5, 5))
    assert(AppVersion(1, 0, 0) > AppVersion(1, 0, 0, Some("beta")))
    assert(AppVersion(1, 0, 0, Some("beta2")) > AppVersion(1, 0, 0, Some("beta")))
  }
}
