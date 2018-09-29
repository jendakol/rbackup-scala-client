package lib

import java.time.ZoneId

import better.files.File
import cats.data.NonEmptyList
import cats.instances.string._
import com.typesafe.scalalogging.StrictLogging
import io.circe.Json
import io.circe.generic.extras.auto._
import io.circe.syntax._
import lib.App.parseSafe
import lib.CirceImplicits._
import lib.clientapi.FileTreeNode.{Directory, RegularFile}
import lib.serverapi.RemoteFile

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

    case class Ready(host: String) extends ClientStatus {
      val name: String = "READY"
      val data: Json = parseSafe(s"""{ "host": "$host"}""")
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

  case class FileTree(name: String, children: Option[NonEmptyList[FileTreeNode]]) {
    private val icon: String = "fas fa-folder icon-state-default"

    private val childrenJson = children.map(_.map(_.toJson).toList.mkString("[", ",", "]")).map(",\"children\":" + _).getOrElse("")

    private val displayName = if (name != "") name else "/"

    lazy val toJson: Json = parseSafe {
      s"""{"icon": "$icon", "isLeaf": false, "opened": false, "value": "$name/", "text": "$displayName", "isFile": false, "isVersion": false, "isDir": true$childrenJson}"""
    }

    def isEmpty: Boolean = children.isEmpty

    lazy val allFiles: Option[NonEmptyList[RegularFile]] = {
      def flatten(children: Option[NonEmptyList[FileTreeNode]]): List[RegularFile] =
        children
          .map(_.collect {
            case f: RegularFile => List(f)
            case Directory(_, _, dirChildren) => flatten(dirChildren)
          }.flatten)
          .getOrElse(List.empty)

      NonEmptyList.fromList(flatten(children))
    }

    def +(other: FileTree): NonEmptyList[FileTree] = FileTree.combine(this, other)
  }

  object FileTree {
    def fromNodes(nodes: FileTreeNode*): Seq[FileTree] = {
      logger.trace(s"Converting to file tree: $nodes")

      nodes
        .groupBy(n => extractTreeName(n.path))
        .map { case (name, nds) => FileTree(name, NonEmptyList.fromList(nds.toList)) }
        .toSeq
    }

    def fromRemoteFiles(files: Seq[RemoteFile]): Seq[FileTree] = {
      logger.trace(s"Converting to file tree: $files")

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
                      children1: Option[NonEmptyList[FileTreeNode]],
                      children2: Option[NonEmptyList[FileTreeNode]]): Directory = {
        (children1, children2) match {
          case (Some(ch), None) => Directory(path, name, Option(ch.sortBy(_.name)))
          case (None, Some(ch)) => Directory(path, name, Option(ch.sortBy(_.name)))
          case (None, None) => Directory(path, name, None)
          case (Some(ch1), Some(ch2)) =>
            val mergedChildren = (ch1 ::: ch2)
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
              .toList
              .sortBy(_.name)

            Directory(path, name, NonEmptyList.fromList(mergedChildren))
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
              val mergedChildren = (ch1 ::: ch2)
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
                .toList
                .sortBy(_.name)

              FileTree(treeName, NonEmptyList.fromList(mergedChildren))
          }
        }
      }
    }

    private def process(file: RemoteFile): FileTree = {
      val parts = file.originalName.split("/")

      val currentPath = parts.head
      val originalPath = file.originalName
      val versions = Option(file.versions.map(Version(file.originalName, _))).filter(_.nonEmpty)

      logger.debug(s"Processing $file")

      FileTree(
        name = extractTreeName(file.originalName),
        children =
          process(parts.tail, currentPath + "/" + parts.tail.headOption.getOrElse("/"), originalPath, versions).map(NonEmptyList.of(_))
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
              children = process(parts.tail, currentPath + "/" + parts.tail.head, originalPath, versions).map(NonEmptyList.of(_))
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

    case class Directory(path: String, name: String, children: Option[NonEmptyList[FileTreeNode]]) extends FileTreeNode {
      private val icon: String = "fas fa-folder icon-state-default"

      private val childrenJson = children.map(_.map(_.toJson).toList.mkString("[", ",", "]")).map(",\"children\":" + _).getOrElse("")

      lazy val toJson: Json = parseSafe {
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
