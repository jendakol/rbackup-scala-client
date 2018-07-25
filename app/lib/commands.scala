package lib

import io.circe.Json

sealed trait Command

object Command{
  def apply(name: String, data: Option[Json]): Option[Command] = name match {
    case "ping" => Some(PingCommand)
    case _ => None
  }
}

case object PingCommand extends Command
