package lib

import better.files.File
import io.circe.Json
import lib.App.parseSafe

object clientapi {

  sealed trait FileTreeNode {
    def toJson: Json
  }

  object FileTreeNode {

    case class Directory(value: String, text: String) extends FileTreeNode {
      private val icon: String = "fas fa-folder icon-state-default"

      def toJson: Json = parseSafe {
        s"""{"icon": "$icon", "isLeaf": false, "value": "$value", "text": "$text", "isFile": false, "isVersion": false, "isDir": true}"""
      }
    }

    object Directory {
      def apply(file: File): Directory = {
        val n = file.name

        new Directory(
          file.path.toAbsolutePath.toString,
          if (n != "") n else "/"
        )
      }
    }

    case class RegularFile(value: String, text: String, versions: Option[Seq[FileVersion]]) extends FileTreeNode {
      private val icon: String = "fas fa-file icon-state-default"

      private val isLeaf: Boolean = !versions.exists(_.nonEmpty)

      private val versionsJson = versions.map(_.map(_.toJson).mkString("[", ", ", "]")).map(""", "children": """ + _).getOrElse("")

      val toJson: Json = parseSafe {
        s"""{"icon": "$icon", "isLeaf": $isLeaf, "value": "$value", "text": "$text" $versionsJson, "isFile": true, "isVersion": false, "isDir": false}"""
      }
    }

    object RegularFile {
      def apply(file: File, versions: Option[Seq[serverapi.FileVersion]]): RegularFile = {
        new RegularFile(
          value = file.path.toAbsolutePath.toString,
          text = file.name,
          versions = versions.map(_.map(FileTreeNode.FileVersion(_)))
        )
      }
    }

    case class FileVersion(value: String, text: String) extends FileTreeNode {

      private val icon: String = "fas fa-clock"

      val toJson: Json = parseSafe {
        s"""{"icon": "$icon", "isLeaf": true, "value": "$value", "text": "$text", "isFile": false, "isVersion": true, "isDir": false}"""
      }
    }

    object FileVersion {
      def apply(fileVersion: lib.serverapi.FileVersion): FileVersion = new FileVersion(
        value = fileVersion.version.toString,
        text = App.DateTimeFormatter.format(fileVersion.created)
      )
    }

  }

}
