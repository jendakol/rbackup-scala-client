import TestOps._
import better.files.File
import com.typesafe.config.ConfigFactory
import lib.serverapi.DownloadResponse.Downloaded
import lib.serverapi.ListFilesResponse.FilesList
import lib.serverapi.UploadResponse.Uploaded
import lib.serverapi._
import lib.{CloudConnector, ServerSession, Sha256}
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

    val LoginResponse.SessionCreated(session) = connector.login(rootUri, "rbackup-test", username, "password").unwrappedFutureValue

    assertResult(rootUri)(session.rootUri)
  }

  test("upload, list and download") {
    val theFile = File(getClass.getClassLoader.getResource("resources/theFileToBeUploaded.dat"))

    // login
    val username = randomString(10)
    connector.registerAccount(rootUri, username, "password").unwrappedFutureValue
    val LoginResponse.SessionCreated(session) = connector.login(rootUri, "rbackup-test", username, "password").unwrappedFutureValue
    implicit val s: ServerSession = session

    // list files

    val ListFilesResponse.FilesList(emptyFiles) = connector.listFiles(None).unwrappedFutureValue
    assertResult(Seq.empty)(emptyFiles)

    // upload

    val UploadResponse.Uploaded(remoteFile1) = connector.upload(theFile)((_, _, _) => ()).unwrappedFutureValue

    assertResult(theFile.pathAsString)(remoteFile1.originalName)
    assertResult("rbackup-test")(remoteFile1.deviceId)

    val Vector(version1) = remoteFile1.versions

    assertResult(1520)(version1.size)
    assertResult(Sha256(theFile.sha256))(version1.hash)

    // list files again - now contains the file

    val ListFilesResponse.FilesList(files1) = connector.listFiles(None).unwrappedFutureValue
    assertResult(Seq(remoteFile1))(files1)
    assertResult(theFile.pathAsString)(files1.head.originalName)

    // upload again

    val UploadResponse.Uploaded(remoteFile2) = connector.upload(theFile)((_, _, _) => ()).unwrappedFutureValue

    assertResult(theFile.pathAsString)(remoteFile2.originalName)
    assertResult("rbackup-test")(remoteFile2.deviceId)

    assertResult(2)(remoteFile2.versions.size)

    val Some(version2) = remoteFile2.versions.tail.headOption

    assertResult(1520)(version2.size)
    assertResult(Sha256(theFile.sha256))(version1.hash)

    // list files again - now contains two revisions of the file

    val ListFilesResponse.FilesList(files2) = connector.listFiles(None).unwrappedFutureValue
    assertResult(1)(files2.size)
    assertResult(Seq(remoteFile2))(files2)

    // download the first version

    val dest = File.newTemporaryFile(prefix = "rbackup-test_")
    val DownloadResponse.Downloaded(finalDestFile, remVer) = connector.download(version1, dest).unwrappedFutureValue

    assertResult(dest)(finalDestFile)
    assertResult(version1)(remVer)
    assertResult(dest.sha256)(theFile.sha256)
  }

  // TODO remove
}
