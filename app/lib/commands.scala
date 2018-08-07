package lib

import io.circe.Json
import io.circe.generic.auto._

sealed trait Command

object Command {
  def apply(name: String, data: Option[Json]): Option[Command] = name match {
    case "ping" => Some(PingCommand)
    case "dirList" => data.flatMap(_.hcursor.get[String]("path").toOption).map(DirListCommand)
    case "saveFileTree" => data.flatMap(_.as[Seq[FileFromTree]].toOption).map(SaveFileTreeCommand)
    case _ => None
  }
}

case object PingCommand extends Command

case class DirListCommand(path: String) extends Command

case class SaveFileTreeCommand(files: Seq[FileFromTree]) extends Command

case class FileFromTree(selected: Boolean, loading: Boolean, value: String, children: Seq[FileFromTree]) {
  def flatten: Seq[FileFromTree] = {
    (this +: children.flatMap(_.flatten)).filterNot(_.loading)
  }

  def toIterable: Iterable[FileFromTree] = new Iterable[FileFromTree] {
    override def iterator: Iterator[FileFromTree] = FileFromTree.this.flatten.iterator
  }

}

//po aktualizaci stromu zresetovat veškeré zpracovávání, zrušit watchery - nastavit vše znovu
