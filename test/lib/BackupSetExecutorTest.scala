package lib

import better.files.File
import cats.data.EitherT
import com.typesafe.scalalogging.StrictLogging
import controllers.WsApiController
import lib.App._
import lib.db.Dao
import lib.server.serverapi.{RemoteFile, UploadResponse}
import lib.settings.Settings
import monix.eval.Task
import monix.execution.Scheduler
import monix.execution.Scheduler.Implicits.global
import org.http4s.Uri
import org.mockito.ArgumentMatchers
import org.mockito.Mockito._
import org.mockito.invocation.InvocationOnMock
import org.scalatest.concurrent.{Eventually, ScalaFutures}
import org.scalatest.mockito.MockitoSugar
import org.scalatest.time.{Seconds, Span}
import scalikejdbc.{ConnectionPool, DB, _}
import utils.TestOps.ResultOps

import scala.concurrent.duration._

class BackupSetExecutorTest extends TestWithDB with MockitoSugar with ScalaFutures with StrictLogging with Eventually {

  private implicit val pat: PatienceConfig = PatienceConfig(timeout = Span(5, Seconds))

  private implicit val ss: ServerSession = ServerSession(Uri.unsafeFromString("http://localhost"), "sesssionId", AppVersion(0, 1, 0))
  private val blockingScheduler = Scheduler.io()
  private val dao = new Dao(blockingScheduler)

  test("doesn't execute backup set twice in parallel") {
    val filesHandler: FilesHandler = mock[FilesHandler]
    when(filesHandler.upload(ArgumentMatchers.any())(ArgumentMatchers.any())).thenReturn(EitherT {
      Task {
        Right(List(Some(UploadResponse.Uploaded(mock[RemoteFile])))): Either[AppException, List[Option[UploadResponse]]]
      }.delayResult(1.second)
    })

    val tasksManager: TasksManager = mock[TasksManager]
    when(tasksManager.start(ArgumentMatchers.any())(ArgumentMatchers.any())).thenAnswer((invocation: InvocationOnMock) => {
      invocation.getArgument[Result[Unit]](1)
    })

    val wsApiController: WsApiController = mock[WsApiController]
    when(wsApiController.send(ArgumentMatchers.any(), ArgumentMatchers.any(), ArgumentMatchers.any())).thenReturn(pureResult(()))

    val bse = new BackupSetsExecutor(dao, filesHandler, tasksManager, wsApiController, blockingScheduler, new Settings(dao))

    assertResult(Right(List.empty))(bse.executeWaitingBackupSets().futureValue)

    val bs1 = dao.createBackupSet("ahoj").unwrappedFutureValue
    val bs2 = dao.createBackupSet("ahoj2").unwrappedFutureValue

    dao.updateFilesInBackupSet(bs1.id, Seq(File("/tmp/1"), File("/tmp/2"))).unwrappedFutureValue
    dao.updateFilesInBackupSet(bs2.id, Seq(File("/tmp/3"), File("/tmp/4"))).unwrappedFutureValue

    dao.markAsProcessing(bs1.id, processing = false).unwrappedFutureValue
    dao.markAsProcessing(bs2.id, processing = false).unwrappedFutureValue

    // execute the backup
    val executed = bse.executeWaitingBackupSets()

    Thread.sleep(300) // delay which the backup sets need to be started

    assertResult(Right(List.empty))(bse.executeWaitingBackupSets().futureValue)

    assertResult(Right(List(bs1, bs2).map(_.id)))(executed.futureValue.map(_.map(_.id))) // test this AFTER the previous test
  }
}
