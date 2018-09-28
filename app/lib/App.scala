package lib

import cats.data.EitherT
import cats.syntax.either._
import com.avast.metrics.scalaapi.Timer
import io.circe.Json
import monix.eval.Task

object App {
  type Result[A] = EitherT[Task, AppException, A]

  def pureResult[A](a: => A): Result[A] = {
    EitherT[Task, AppException, A](Task(Right(a)))
  }

  def failedResult[A](e: AppException): Result[A] = {
    EitherT[Task, AppException, A](Task.now(Left(e)))
  }

  def parseSafe(str: String): Json = io.circe.parser.parse(str).getOrElse(throw new RuntimeException(s"BUG :-( - could not parse\n$str"))

  final val DateTimeFormatter = java.time.format.DateTimeFormatter.ofPattern("dd.MM.yyyy, HH:mm:ss")

  implicit class CirceOps[A](val e: Either[io.circe.Error, A]) {
    def toResult[AA >: A]: Result[AA] = EitherT.fromEither[Task](e.leftMap(AppException.ParsingFailure("", _)))
  }

  implicit class ResultOps[A](val r: Result[A]) extends AnyVal {

    /** Adds asynchronous callback to the `Result` and returns new `Result` which contains the callback. Has to be part of the chain.
      */
    def withResult(f: PartialFunction[Either[AppException, A], Any]): Result[A] = {
      r.transform { ei =>
        if (f.isDefinedAt(ei)) f.apply(ei)
        ei
      }
    }

    def restartIf(cond: Throwable => Boolean): Result[A] = EitherT {
      r.value.onErrorRestartIf(cond)
    }

    /** Measures time from start of the task to the place in the chain where this method is called. Measures only succeeded tasks.
      * Has to be part of the chain.
      */
    def measured(successTimer: Timer): Result[A] = {
      pureResult(successTimer.start())
        .flatMap { ctx =>
          r.withResult {
            case Right(_) => ctx.stop()
          }
        }
    }

    /** Measures time from start of the task to the place in the chain where this method is called. Measures both succeeded and failed.
      * Has to be part of the chain.
      */
    def measured(successTimer: Timer, failureTimer: Timer): Result[A] = {
      pureResult((successTimer.start(), failureTimer.start()))
        .flatMap {
          case (successCtx, failureCtx) =>
            r.withResult {
              case Right(_) => successCtx.stop()
              case Left(_) => failureCtx.stop()
            }
        }
    }
  }

}

case class SessionId(value: String) extends AnyVal

case class DeviceId(value: String) extends AnyVal
