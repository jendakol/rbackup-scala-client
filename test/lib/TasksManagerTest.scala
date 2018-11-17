package lib

import java.util.UUID

import cats.data.EitherT
import controllers.{WsApiController, WsMessage}
import io.circe.Json
import io.circe.generic.extras.auto._
import lib.App._
import lib.CirceImplicits._
import monix.eval.Task
import monix.execution.Scheduler.Implicits.global
import org.mockito.Mockito._
import org.mockito.{ArgumentCaptor, ArgumentMatchers}
import org.scalatest.FunSuite
import org.scalatest.mockito.MockitoSugar

import scala.concurrent.duration._

class TasksManagerTest extends FunSuite with MockitoSugar {
  test("run and end") {
    val runningTask = RunningTask.FileUpload("theName")

    val wsApi = mock[WsApiController]
    when(wsApi.send(ArgumentMatchers.any())).thenReturn(pureResult(()))

    val manager = new TasksManager(wsApi)

    var started = false
    var finished = false

    val startTask = manager.start(runningTask) {
      EitherT.right(Task {
        started = true
      }.delayResult(1.second).map(_ => finished = true))
    }

    assertResult(false)(started)
    assertResult(false)(finished)
    verify(wsApi, times(0)).send(ArgumentMatchers.any())

    startTask.value.runSyncUnsafe(Duration.Inf)

    {
      val captor = ArgumentCaptor.forClass[WsMessage, WsMessage](classOf)

      assertResult(true)(started)
      assertResult(false)(finished)
      verify(wsApi, times(1)).send(captor.capture())

      assertResult(Seq(runningTask.toJson)) {
        captor.getValue.data.as[Map[UUID, Json]].getOrElse(fail()).values.toSeq
      }
    }

    Thread.sleep(1000)

    {
      val captor = ArgumentCaptor.forClass[WsMessage, WsMessage](classOf)

      assertResult(true)(started)
      assertResult(true)(finished)
      verify(wsApi, times(2)).send(captor.capture())

      assertResult(Seq.empty) {
        captor.getValue.data.as[Map[UUID, Json]].getOrElse(fail()).values.toSeq
      }
    }
  }

  test("run and fail") {
    val runningTask = RunningTask.FileUpload("theName")

    val wsApi = mock[WsApiController]
    when(wsApi.send(ArgumentMatchers.any())).thenReturn(pureResult(()))

    val manager = new TasksManager(wsApi)

    var started = false
    var finished = false

    val startTask = manager.start(runningTask) {
      EitherT.right(Task {
        started = true
      }.delayResult(1.second).map(_ => finished = true).map(_ => sys.error("blah")))
    }

    assertResult(false)(started)
    assertResult(false)(finished)
    verify(wsApi, times(0)).send(ArgumentMatchers.any())

    startTask.value.runSyncUnsafe(Duration.Inf)

    {
      val captor = ArgumentCaptor.forClass[WsMessage, WsMessage](classOf)

      assertResult(true)(started)
      assertResult(false)(finished)
      verify(wsApi, times(1)).send(captor.capture())

      assertResult(Seq(runningTask.toJson)) {
        captor.getValue.data.as[Map[UUID, Json]].getOrElse(fail()).values.toSeq
      }
    }

    Thread.sleep(1500)

    {
      val captor = ArgumentCaptor.forClass[WsMessage, WsMessage](classOf)

      assertResult(true)(started)
      assertResult(true)(finished)
      verify(wsApi, times(2)).send(captor.capture())

      assertResult(Seq.empty) {
        captor.getValue.data.as[Map[UUID, Json]].getOrElse(fail()).values.toSeq
      }
    }
  }

  test("run and cancel") {
    val runningTask = RunningTask.FileUpload("theName")

    val wsApi = mock[WsApiController]
    when(wsApi.send(ArgumentMatchers.any())).thenReturn(pureResult(()))

    val manager = new TasksManager(wsApi)

    var started = false
    var finished = false
    var cancelled = false

    val startTask = manager.start(runningTask) {
      EitherT.right(
        Task {
          started = true
        }.delayResult(1.second)
          .doOnCancel(Task {
            cancelled = true
          })
          .map(_ => finished = true))
    }

    assertResult(false)(started)
    assertResult(false)(finished)
    assertResult(false)(cancelled)

    verify(wsApi, times(0)).send(ArgumentMatchers.any())

    startTask.value.runSyncUnsafe(Duration.Inf)

    val (key, _) = {
      val captor = ArgumentCaptor.forClass[WsMessage, WsMessage](classOf)

      assertResult(true)(started)
      assertResult(false)(finished)
      assertResult(cancelled)(finished)
      verify(wsApi, times(1)).send(captor.capture())

      val tasks = captor.getValue.data.as[Map[UUID, Json]].getOrElse(fail()).toSeq

      assertResult(Seq(runningTask.toJson)) {
        tasks.map(_._2)
      }

      tasks.head
    }

    manager.cancel(key).value.runSyncUnsafe(Duration.Inf)

    Thread.sleep(1000)

    {
      val captor = ArgumentCaptor.forClass[WsMessage, WsMessage](classOf)

      assertResult(true)(started)
      assertResult(false)(finished)
      assertResult(true)(cancelled)
      verify(wsApi, times(2)).send(captor.capture())

      assertResult(Seq.empty) {
        captor.getValue.data.as[Map[UUID, Json]].getOrElse(fail()).values.toSeq
      }
    }
  }
}
