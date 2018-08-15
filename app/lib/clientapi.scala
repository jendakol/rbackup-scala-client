package lib

import java.time.ZoneId

import better.files.File
import cats.data.NonEmptyList
import io.circe.Json
import io.circe.generic.extras.auto._
import io.circe.syntax._
import lib.App.parseSafe
import lib.CirceImplicits._
import lib.clientapi.FileTreeNode.{Directory, RegularFile}
import lib.serverapi.RemoteFile

object clientapi {

  sealed trait ClientStatus {
    def name: String
  }

  object ClientStatus {

    case object Installed extends ClientStatus {
      val name: String = "INSTALLED"
    }

    case object Ready extends ClientStatus {
      val name: String = "READY"
    }

    case object Disconnected extends ClientStatus {
      val name: String = "DISCONNECTED"
    }

    case object Initializing extends ClientStatus {
      val name: String = "INITIALIZING"
    }

  }

  case class FileTree(name: String, children: Option[Vector[FileTreeNode]]) {
    private val icon: String = "fas fa-folder icon-state-default"

    private val childrenJson = children.map(_.map(_.toJson).mkString("[", ",", "]")).map(",\"children\":" + _).getOrElse("")

    private val displayName = if (name != "") name else "/"

    def toJson: Json = parseSafe {
      s"""{"icon": "$icon", "isLeaf": false, "opened": false, "value": "$name/", "text": "$displayName", "isFile": false, "isVersion": false, "isDir": true$childrenJson}"""
    }

    def +(other: FileTree): NonEmptyList[FileTree] = FileTree.combine(this, other)
  }

  object FileTree {
    def fromNodes(nodes: FileTreeNode*): Seq[FileTree] = {
      nodes
        .groupBy(n => extractTreeName(n.path))
        .map { case (name, nds) => FileTree(name, Option(nds.toVector).filter(_.nonEmpty)) }
        .toSeq
    }

    def fromRemoteFiles(files: Seq[RemoteFile]): Seq[FileTree] = {
      files.map(process).foldLeft[Seq[FileTree]](Seq.empty) {
        case (seq, ft) =>
          val (sameName, differentName) = seq.partition(_.name == ft.name)

          sameName.headOption.map(_ + ft).map(_.toList).getOrElse(Seq(ft)) ++ differentName
      }
    }

    implicit class FileTreeListOps(val seq: Seq[FileTree]) extends AnyVal {
      def toJson: Json = seq.map(_.toJson).asJson
    }

    private def extractTreeName(path: String): String = {
      path.split("/").head
    }

    private def combine(tree1: FileTree, tree2: FileTree): NonEmptyList[FileTree] = {
      def combineDirs(path: String,
                      name: String,
                      children1: Option[Vector[FileTreeNode]],
                      children2: Option[Vector[FileTreeNode]]): Directory = {
        (children1, children2) match {
          case (Some(ch), None) => Directory(path, name, Option(ch.sortBy(_.name)))
          case (None, Some(ch)) => Directory(path, name, Option(ch.sortBy(_.name)))
          case (None, None) => Directory(path, name, None)
          case (Some(ch1), Some(ch2)) =>
            val mergedChildren = (ch1 ++ ch2)
              .groupBy(_.path)
              .values
              .map { v =>
                if (v.tail.nonEmpty) {
                  v.reduce[FileTreeNode] {
                    case (file1: RegularFile, file2: RegularFile) if file1 == file2 => file1
                    case (dir1: Directory, dir2: Directory) if dir1 == dir2 => dir1.copy(children = dir1.children.map(_.sortBy(_.name)))
                    case (_: RegularFile, _: Directory) =>
                      throw new IllegalStateException("Merging two non-equal nodes; one is directory and one is file")
                    case (_: Directory, _: RegularFile) =>
                      throw new IllegalStateException("Merging two non-equal nodes; one is directory and one is file")
                    case (dir1: Directory, dir2: Directory) => combineDirs(dir1.path, dir1.name, dir1.children, dir2.children)
                  }
                } else {
                  v.head
                }
              }
              .toVector
              .sortBy(_.name)

            Directory(path, name, Option(mergedChildren))
        }
      }

      if (tree1.name != tree2.name) {
        NonEmptyList.of(tree1, tree2)
      } else {
        val treeName = tree1.name

        NonEmptyList.of {
          (tree1.children, tree2.children) match {
            case (Some(ch), None) => FileTree(treeName, Some(ch.sortBy(_.name)))
            case (None, Some(ch)) => FileTree(treeName, Some(ch.sortBy(_.name)))
            case (None, None) => FileTree(treeName, None)
            case (Some(ch1), Some(ch2)) =>
              val mergedChildren = (ch1 ++ ch2)
                .groupBy(_.path)
                .values
                .map { v =>
                  if (v.tail.nonEmpty) {
                    v.reduce[FileTreeNode] {
                      case (file1: RegularFile, file2: RegularFile) if file1 == file2 => file1
                      case (dir1: Directory, dir2: Directory) if dir1 == dir2 => dir1.copy(children = dir1.children.map(_.sortBy(_.name)))
                      case (_: RegularFile, _: Directory) =>
                        throw new IllegalStateException("Merging two non-equal nodes; one is directory and one is file")
                      case (_: Directory, _: RegularFile) =>
                        throw new IllegalStateException("Merging two non-equal nodes; one is directory and one is file")
                      case (dir1: Directory, dir2: Directory) => combineDirs(dir1.path, dir1.name, dir1.children, dir2.children)
                    }
                  } else {
                    v.head
                  }
                }
                .toVector
                .sortBy(_.name)

              FileTree(treeName, Option(mergedChildren))
          }
        }
      }
    }

    private def process(f: RemoteFile): FileTree = {
      val parts = f.originalName.split("/")

      val currentPath = parts.head
      val originalPath = f.originalName
      val versions = Option(f.versions.map(Version(f.originalName, _))).filter(_.nonEmpty)

      FileTree(
        name = extractTreeName(f.originalName),
        children = process(parts.tail, currentPath + "/" + parts.tail.head, originalPath, versions).map(Vector(_))
      )
    }

    private def process(pathTail: Array[String],
                        currentPath: String,
                        originalPath: String,
                        versions: Option[Vector[Version]]): Option[FileTreeNode] = {
      pathTail match {
        case Array(last) => Option(RegularFile(path = originalPath, name = last, versions = versions))
        case parts =>
          Option {
            Directory(
              path = currentPath,
              name = parts.head,
              children = process(parts.tail, currentPath + "/" + parts.tail.head, originalPath, versions).map(Vector(_))
            )
          }
      }
    }
  }

  case class Version(value: String, text: String, path: String, versionId: Long) {

    private val icon: String = "fas fa-clock"

    val toJson: Json = parseSafe {
      s"""{"icon": "$icon", "isLeaf": true, "value": "$value", "text": "$text", "path": "$path", "versionId": $versionId, "isFile": false, "isVersion": true, "isDir": false}"""
    }
  }

  object Version {
    def apply(path: String, fileVersion: lib.serverapi.RemoteFileVersion): Version = new Version(
      value = fileVersion.version.toString,
      text = App.DateTimeFormatter.format(fileVersion.created.withZoneSameInstant(ZoneId.systemDefault())),
      path = path,
      versionId = fileVersion.version
    )
  }

  sealed trait FileTreeNode {
    def path: String

    def name: String

    def toJson: Json
  }

  object FileTreeNode {

    case class Directory(path: String, name: String, children: Option[Vector[FileTreeNode]]) extends FileTreeNode {
      private val icon: String = "fas fa-folder icon-state-default"

      private val childrenJson = children.map(_.map(_.toJson).mkString("[", ",", "]")).map(",\"children\":" + _).getOrElse("")

      def toJson: Json = parseSafe {
        s"""{"icon": "$icon", "isLeaf": false, "opened": false, "value": "$path", "text": "$name", "isFile": false, "isVersion": false, "isDir": true$childrenJson}"""
      }
    }

    object Directory {
      def apply(file: File): Directory = {
        val n = file.name

        new Directory(
          file.path.toAbsolutePath.toString,
          if (n != "") n else "/",
          None
        )
      }
    }

    case class RegularFile(path: String, name: String, versions: Option[Seq[Version]]) extends FileTreeNode {
      private val icon: String = "fas fa-file icon-state-default"

      private val isLeaf: Boolean = !versions.exists(_.nonEmpty)

      private val versionsJson = versions.map(_.map(_.toJson).mkString("[", ", ", "]")).map(""", "children": """ + _).getOrElse("")

      val toJson: Json = parseSafe {
        s"""{"icon": "$icon", "isLeaf": $isLeaf, "opened": false, "value": "$path", "text": "$name" $versionsJson, "isFile": true, "isVersion": false, "isDir": false}"""
      }
    }

    object RegularFile {
      def apply(file: File, versions: Option[Seq[serverapi.RemoteFileVersion]]): RegularFile = {
        new RegularFile(
          path = file.path.toAbsolutePath.toString,
          name = file.name,
          versions = versions.map(_.map(Version(file.pathAsString, _)))
        )
      }
    }

  }

}
