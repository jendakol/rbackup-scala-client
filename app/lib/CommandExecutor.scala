package lib

import java.util.concurrent.TimeUnit

import com.typesafe.scalalogging.StrictLogging
import io.circe.Json
import io.circe.syntax._
import javax.inject.{Inject, Singleton}
import monix.execution.Scheduler

import scala.util.Try

@Singleton
class CommandExecutor @Inject()(scheduler: Scheduler) extends StrictLogging{
  def execute(command: Command, send: Json => Try[Unit]): Unit = command match {
    case PingCommand =>
      scheduler.scheduleAtFixedRate(0, 1, TimeUnit.SECONDS, () => {
        send("{}".asJson).failed.foreach(logger.warn("Error while sending message", _))
      })

  }
}
