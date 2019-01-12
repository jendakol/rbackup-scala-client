package lib.client

import java.nio.file.Paths
import java.time.ZoneId

import better.files.File
import cats.data.NonEmptyList
import cats.instances.string._
import com.typesafe.scalalogging.StrictLogging
import io.circe.generic.extras.Configuration
import io.circe.syntax._
import io.circe.{Decoder, Json}
import lib.{App, AppVersion}
import lib.App._
import lib.App.StringOps
import lib.client.clientapi.FileTreeNode.{Directory, RegularFile}
import lib.server.serverapi
import lib.server.serverapi.{RemoteFile, RemoteFileVersion}
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

  case class FileTree(name: String, children: Option[NonEmptyList[FileTreeNode]]) {
    private val icon: String = "fas fa-folder icon-state-default"

    private val childrenJson = children.map(_.map(_.toJson).toList.mkString("[", ",", "]")).map(",\"children\":" + _).getOrElse("")

    private val displayName = if (name != "") name else "/"

    lazy val toJson: Json = parseUnsafe {
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
      val fixedName = file.originalName.fixPath
      val parts = fixedName.split("/")

      val currentPath = parts.head
      val versions = Option(file.versions.map(Version(fixedName, _))).filter(_.nonEmpty)

      logger.trace(s"Processing $file")

      FileTree(
        name = extractTreeName(fixedName),
        children =
          process(parts.tail, currentPath + "/" + parts.tail.headOption.getOrElse("/"), fixedName, versions).map(NonEmptyList.of(_))
      )
    }

    private def process(pathTail: Array[String],
                        currentPath: String,
                        originalPath: String,
                        versions: Option[Vector[Version]]): Option[FileTreeNode] = {
      pathTail.length match {
        case 0 => None
        case 1 => Option(RegularFile(path = originalPath, name = pathTail.head, versions = versions))
        case _ =>
          Option {
            Directory(
              path = currentPath,
              name = pathTail.head,
              children = process(pathTail.tail, currentPath + "/" + pathTail.tail.head, originalPath, versions).map(NonEmptyList.of(_))
            )
          }
      }
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

    def toJson: Json
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

    case class RegularFile(path: String, name: String, versions: Option[Seq[Version]]) extends FileTreeNode {
      private val icon: String = "fas fa-file icon-state-default"

      private val isLeaf: Boolean = !versions.exists(_.nonEmpty)

      private val versionsJson = versions.map(_.map(_.toJson).mkString("[", ", ", "]")).map(""", "children": """ + _).getOrElse("")

      val toJson: Json = parseUnsafe {
        s"""{"icon": "$icon", "isLeaf": $isLeaf, "opened": false, "value": ${path.asJson}, "text": "$name" $versionsJson, "isFile": true, "isVersion": false, "isDir": false}"""
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
