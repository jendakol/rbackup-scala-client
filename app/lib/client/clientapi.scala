package lib.client

import java.nio.file.Paths
import java.time.ZoneId

import better.files.File
import cats.data.NonEmptyList
import com.typesafe.scalalogging.StrictLogging
import io.circe.generic.extras.Configuration
import io.circe.syntax._
import io.circe.{Decoder, Json}
import lib.App.{StringOps, _}
import lib.server.serverapi
import lib.server.serverapi.RemoteFileVersion
import lib.{App, AppVersion}
import org.http4s.Uri

object clientapi extends StrictLogging {

  sealed trait ClientStatus {
    def name: String

    def data: Json
  }

  object ClientStatus {

    case object Installed extends ClientStatus {
      val name: String = "INSTALLED"
      val data: Json = Json.Null
    }

    case class Ready(rootUri: Uri, serverVersion: AppVersion) extends ClientStatus {
      val name: String = "READY"
      val data: Json = parseUnsafe(s"""{ "host": "$rootUri", "serverVersion":"$serverVersion"}""")
    }

    case object Disconnected extends ClientStatus {
      val name: String = "DISCONNECTED"
      val data: Json = Json.Null
    }

    case object Initializing extends ClientStatus {
      val name: String = "INITIALIZING"
      val data: Json = Json.Null
    }

  }

  case class Version(value: String, text: String, path: String, versionId: Long) {

    private val icon: String = "fas fa-clock"

    val toJson: Json = parseUnsafe {
      s"""{"icon": "$icon", "isLeaf": true, "value": "$value", "text": "$text", "path": ${path.asJson}, "versionId": $versionId, "isFile": false, "isVersion": true, "isDir": false}"""
    }
  }

  object Version {
    def apply(path: String, fileVersion: RemoteFileVersion): Version = new Version(
      value = fileVersion.version.toString,
      text = App.DateTimeFormatter.format(fileVersion.mtime.withZoneSameInstant(ZoneId.systemDefault())),
      path = path,
      versionId = fileVersion.version
    )
  }

  sealed trait FileTreeNode {
    def path: String

    def name: String

    def toJson: Json // TODO replace with implicit encoder
  }

  object FileTreeNode {

    case class Directory(path: String, name: String, children: Option[NonEmptyList[FileTreeNode]]) extends FileTreeNode {
      private val icon: String = "fas fa-folder icon-state-default"

      private val childrenJson = children.map(_.map(_.toJson).toList.mkString("[", ",", "]")).map(",\"children\":" + _).getOrElse("")

      lazy val toJson: Json = parseUnsafe {
        s"""{"icon": "$icon", "isLeaf": false, "opened": false, "value": ${path.asJson}, "text": "$name", "isFile": false, "isVersion": false, "isDir": true$childrenJson}"""
      }
    }

    object Directory {
      def apply(file: File): Directory = {
        val absolutePath = file.path.toAbsolutePath.toString
        val n = absolutePath.fixPath.split("/").lastOption

        new Directory(
          absolutePath,
          n.map(p => if (p != "") p else "/").getOrElse("/"),
          None
        )
      }
    }

    case class RegularFile(path: String, name: String, versions: Option[NonEmptyList[Version]]) extends FileTreeNode {
      private val icon: String = "fas fa-file icon-state-default"

      private val isLeaf: Boolean = versions.nonEmpty

      private val versionsJson = versions.map(_.map(_.toJson).toList.mkString("[", ", ", "]")).map(""", "children": """ + _).getOrElse("")

      val toJson: Json = parseUnsafe {
        s"""{"icon": "$icon", "isLeaf": $isLeaf, "opened": false, "value": ${path.asJson}, "text": "$name" $versionsJson, "isFile": true, "isVersion": false, "isDir": false}"""
      }
    }

    object RegularFile {
      def apply(file: File, versions: Option[NonEmptyList[serverapi.RemoteFileVersion]]): RegularFile = {
        new RegularFile(
          path = file.path.toAbsolutePath.toString,
          name = file.name,
          versions = versions.map(_.map(Version(file.pathAsString, _)))
        )
      }
    }

  }

  case class BackupSetNode(selected: Boolean,
                           loading: Boolean,
                           value: String,
                           text: String,
                           isLeaf: Boolean = false,
                           isFile: Boolean = false,
                           isDir: Boolean = false,
                           children: Seq[BackupSetNode],
                           icon: String) {
    def flatten: Seq[BackupSetNode] = {
      (this +: children.flatMap(_.flatten)).filterNot(_.loading)
    }

    def toIterable: Iterable[BackupSetNode] = new Iterable[BackupSetNode] {
      override def iterator: Iterator[BackupSetNode] = BackupSetNode.this.flatten.iterator
    }

    def flattenNormalize: Seq[BackupSetNode] = {
      val map = flatten.filter(_.selected).map(n => Paths.get(n.value) -> n).sortBy(_._1).toMap

      val keys = map.keySet

      map
        .filterKeys { path => // filter out files which are already present vie their parents
          !keys.exists(p => p != path && path.startsWith(p))
        }
        .values
        .toSeq
    }

  }

  object BackupSetNode {

    import io.circe.generic.extras.semiauto._

    implicit val customConfig: Configuration = Configuration.default.withDefaults

    implicit val decoder: Decoder[BackupSetNode] = deriveDecoder
  }

}
