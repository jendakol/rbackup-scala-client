package lib

import cats.data.EitherT
import cats.syntax.either._
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

}

case class SessionId(value: String) extends AnyVal

case class DeviceId(value: String) extends AnyVal
