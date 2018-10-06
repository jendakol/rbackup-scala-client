import TestOps._
import com.typesafe.config.ConfigFactory
import lib.CloudConnector
import lib.serverapi.{LoginResponse, RegistrationResponse}
import monix.execution.Scheduler.Implicits.global
import org.http4s.Uri
import org.scalatest.FunSuite

class IntegrationTest extends FunSuite {
  private val connector = CloudConnector.fromConfig(ConfigFactory.empty())
  private val rootUri = Uri.unsafeFromString("http://localhost:3369")

  test("read status") {
    assertResult("RBackup running")(connector.status(rootUri).unwrappedFutureValue)
  }

  test("register account and login") {
    val username = randomString(10)

    val RegistrationResponse.Created(accountId) = connector.registerAccount(rootUri, username, "password").unwrappedFutureValue

    assert(accountId.nonEmpty)

    val LoginResponse.SessionCreated(session) =
      connector.login(rootUri, "rbackup-test", username, "password").unwrappedFutureValue

    assertResult(rootUri)(session.rootUri)
  }
}
