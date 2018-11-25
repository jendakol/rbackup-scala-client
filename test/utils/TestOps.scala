package utils

import lib.App.Result
import monix.eval.Task
import monix.execution.Scheduler

import scala.util.Random

object TestOps {

  def randomString(length: Int): String = {
    Random.alphanumeric.take(length).mkString("")
  }

  implicit class TaskOps[A](val t: Task[A]) extends AnyVal {
    def futureValue(implicit s: Scheduler): A = t.toIO.unsafeRunSync()
  }

  implicit class ResultOps[A](val t: Result[A]) extends AnyVal {
    def unwrappedFutureValue(implicit s: Scheduler): A = t.value.toIO.unsafeRunSync() match {
      case Right(value) => value
      case Left(ex) => throw ex
    }
  }

}
