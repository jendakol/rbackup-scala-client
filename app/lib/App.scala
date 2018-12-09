package lib

import cats.data.EitherT
import cats.syntax.either._
import com.avast.metrics.scalaapi.Timer
import com.typesafe.scalalogging.StrictLogging
import io.circe.Json
import io.sentry.Sentry
import io.sentry.event.BreadcrumbBuilder
import javax.inject.{Inject, Singleton}
import lib.AppException.MultipleFailuresException
import monix.eval.Task
import monix.execution.{Cancelable, Scheduler}
import org.http4s.Uri
import play.api.inject.ApplicationLifecycle
import updater.{AppVersion, Updater}

import scala.collection.generic.CanBuildFrom
import scala.concurrent.Future
import scala.language.higherKinds

@Singleton
class App @Inject()(backupSetsExecutor: BackupSetsExecutor, updater: Updater)(lifecycle: ApplicationLifecycle)(implicit sch: Scheduler)
    extends StrictLogging {

  lifecycle.addStopHook { () =>
    stop.runAsync
  }

  private val bse: Cancelable = backupSetsExecutor.start
  private val upd: Cancelable = updater.start

  def stop: Task[Unit] = Task {
    logger.info("Shutting down app")
    bse.cancel()
    upd.cancel()
  }
}

object App {
  final val versionStr: String = "0.1.0"
  final val version: AppVersion = AppVersion(versionStr).getOrElse(throw new IllegalArgumentException("Could not parse versionStr"))

  type Result[A] = EitherT[Task, AppException, A]

  def pureResult[A](a: => A): Result[A] = {
    EitherT[Task, AppException, A](Task(Right(a)))
  }

  def failedResult[A](e: AppException): Result[A] = {
    EitherT[Task, AppException, A](Task.now(Left(e)))
  }

  def parseUnsafe(str: String): Json = io.circe.parser.parse(str).getOrElse(throw new RuntimeException(s"BUG :-( - could not parse\n$str"))

  final val JsonSuccess: Json = parseUnsafe("""{ "success": true }""")

  final val DateTimeFormatter = java.time.format.DateTimeFormatter.ofPattern("dd.MM.yyyy, HH:mm:ss")

  implicit class CirceOps[A](val e: Either[io.circe.Error, A]) {
    def toResult[AA >: A]: Result[AA] = EitherT.fromEither[Task](e.leftMap(AppException.ParsingFailure("", _)))
  }

  implicit class ResultOps[A](val r: Result[A]) extends AnyVal {

    def mapToJsonSuccess: Result[Json] = r.map(_ => JsonSuccess)

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

    def doOnCancel(callback: Task[Unit]): Result[A] = EitherT {
      r.value.doOnCancel(callback)
    }

    def unwrapResult: Task[A] = r.value.flatMap {
      case Right(value) => Task.now(value)
      case Left(t) => Task.raiseError(t)
    }

    def runAsync(callback: PartialFunction[Either[Throwable, A], Any])(implicit s: Scheduler): Future[Either[AppException, A]] = {
      r.value.runAsync.andThen {
        case scala.util.Success(ei) => if (callback.isDefinedAt(ei)) callback.apply(ei)
        case scala.util.Failure(exception) =>
          val ei = Left(exception)
          if (callback.isDefinedAt(ei)) callback.apply(ei)
      }
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

  implicit class TaskOps[A](val ta: Task[A]) extends AnyVal {
    def executeOnScheduler(sch: Scheduler): Task[A] = {
      ta.executeOn(sch).asyncBoundary
    }
  }

  implicit class ResultSeqOps[A](val rs: Seq[Result[A]]) extends AnyVal {
    def sequentially: Result[Seq[A]] = EitherT {
      Task.sequence(rs.map(_.value)).map(_.collectPartition).map {
        case (failures, results) =>
          if (failures.nonEmpty) Left(MultipleFailuresException(failures): AppException) else Right(results)
      }
    }

    def inparallel: Result[List[A]] = EitherT {
      Task
        .gatherUnordered(rs.map(_.value))
        .map(_.collectPartition {
          case Right(value) => Right(value)
          case Left(value) => Left(value)
        })
        .map {
          case (failures, results) =>
            if (failures.nonEmpty) Left(MultipleFailuresException(failures): AppException) else Right(results)
        }
    }
  }

  implicit class TraversableOnceHelper[A, Coll[X] <: TraversableOnce[X]](val repr: Coll[A]) extends AnyVal {

    def collectPartition[B, C](implicit ev: A <:< Either[B, C],
                               bfLeft: CanBuildFrom[Coll[Either[B, C]], B, Coll[B]],
                               bfRight: CanBuildFrom[Coll[Either[B, C]], C, Coll[C]]): (Coll[B], Coll[C]) = {

      repr
        .asInstanceOf[Coll[Either[B, C]]]
        .collectPartition {
          case Right(v) => Right(v)
          case Left(v) => Left(v)
        }
    }

    def collectPartition[B, C](f: PartialFunction[A, Either[B, C]])(implicit bfLeft: CanBuildFrom[Coll[A], B, Coll[B]],
                                                                    bfRight: CanBuildFrom[Coll[A], C, Coll[C]]): (Coll[B], Coll[C]) = {
      val left = bfLeft(repr)
      val right = bfRight(repr)

      repr.foreach { e =>
        if (f.isDefinedAt(e)) {
          f(e) match {
            case Left(next) => left += next
            case Right(next) => right += next
          }
        }
      }

      left.result -> right.result
    }
  }

  implicit class StringOps(val s: String) extends AnyVal {
    def fixPath: String = s.replace('\\', '/').replace('\\', '/')
  }

  def leaveBreadcrumb(message: String, data: Map[String, Any] = Map.empty): Unit = {
    Sentry.getContext.recordBreadcrumb(
      data
        .foldLeft(new BreadcrumbBuilder().setMessage(message)) {
          case (builder, (key, value)) =>
            builder.withData(key, value.toString)
        }
        .build()
    )
  }
}

case class ServerSession(rootUri: Uri, sessionId: String)

case class DeviceId(value: String) extends AnyVal
