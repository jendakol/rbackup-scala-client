package lib.server

import java.io.File.{separatorChar => SystemSlashChar}

import better.files.File
import cats.data.NonEmptyList
import cats.syntax.all._
import com.typesafe.scalalogging.StrictLogging
import controllers.WsApiController
import io.circe.Json
import io.circe.generic.extras.auto._
import io.circe.syntax._
import javax.inject.Inject
import lib.App
import lib.App.{StringOps, _}
import lib.client.clientapi.FileTreeNode.Directory
import lib.client.clientapi.{FileTreeNode, Version}
import lib.db.{DbFile, FilesDao}
import lib.server.CloudFilesRegistry._
import lib.server.serverapi.{RemoteFile, RemoteFileVersion}
import org.apache.commons.lang3.StringUtils

class CloudFilesRegistry @Inject()(wsApiController: WsApiController, dao: FilesDao) extends StrictLogging {

  def updateFile(file: File, remoteFile: RemoteFile): Result[Unit] = {
    dao.update(file, remoteFile)
  }

  def versions(file: File): Result[Option[NonEmptyList[RemoteFileVersion]]] = {
    dao.get(file).map(_.flatMap(dbFile => NonEmptyList.fromList(dbFile.remoteFile.versions)))
  }

  def get(file: File): Result[Option[RemoteFile]] = {
    dao.get(file).map(_.map(_.remoteFile))
  }

  def removeFile(file: RemoteFile): Result[Unit] = {
    App.leaveBreadcrumb("Removing file", Map("path" -> file.originalName))

    dao.delete(file) >>
      reportDirUpdate(File(file.originalName).parent)
  }

  def removeFileVersion(file: File, fileVersion: RemoteFileVersion): Result[Unit] = {
    App.leaveBreadcrumb("Removing file version", Map("path" -> file.pathAsString, "id" -> fileVersion.version))

    get(file).flatMap {
      case Some(f) =>
        val restOfVersions = f.versions.filterNot(_ == fileVersion)

        if (restOfVersions.nonEmpty) {
          updateFile(file, f.copy(versions = restOfVersions)) >>
            reportVersionsUpdate(f, restOfVersions)
        } else {
          removeFile(f)
        }

      case None =>
        App.leaveBreadcrumb("Didn't remove file version because it couldn't be found")
        pureResult(())
    }
  }

  private def reportDirUpdate(parent: File): Result[Unit] = {
    listFiles(prefix = Some(parent.pathAsString)).flatMap { content =>
      wsApiController.send(
        `type` = "removedFile",
        data = parseUnsafe {
          s"""{ "parent": ${parent.pathAsString.asJson}, "files": ${content.map(_.toJson).asJson}}"""
        },
        ignoreFailure = false
      )
    }
  }

  private def reportVersionsUpdate(f: RemoteFile, versions: List[RemoteFileVersion]): Result[Unit] = {
    wsApiController.send(
      `type` = "removedFileVersion",
      data = parseUnsafe {
        s"""{ "path": ${f.originalName.asJson}, "versions": ${versions.map(Version(f.originalName, _).toJson).asJson}}"""
      },
      ignoreFailure = false
    )
  }

  def listFiles(prefix: Option[String]): Result[List[FileTreeNode]] = {
    dao.listAll(prefix.getOrElse("")).map { files =>
      if (files.nonEmpty) {
        backedUpList(prefix, files)
      } else {
        List.empty
      }
    }
  }
}

object CloudFilesRegistry {
  private[server] def backedUpList(prefix: Option[String], files: List[DbFile], systemSlash: Char = SystemSlashChar): List[FileTreeNode] = {
    val trailedPrefix = prefix
      .map { pr =>
        if (!pr.endsWith(systemSlash.toString)) {
          pr + systemSlash
        } else pr
      }

    val filteredFiles = trailedPrefix.map(p => files.filter(_.path.startsWith(p))).getOrElse(files)

    val uniqueNames = filteredFiles
      .map { dbFile =>
        import dbFile._
        trailedPrefix
          .map(_.fixPath)
          .map(path.fixPath.stripPrefix)
          .getOrElse(path.fixPath)
          .split("/")
          .headOption
          .filter(StringUtils.isNotBlank)
          .getOrElse("/")
      }
      .distinct
      .sorted

    uniqueNames.map { name =>
      val fullPath = (trailedPrefix.getOrElse("") + name).unfixPath

      filteredFiles.find(_.path == fullPath) match {
        case Some(dbFile) =>
          FileTreeNode.RegularFile(
            path = fullPath,
            name = name,
            versions = NonEmptyList.fromList(dbFile.remoteFile.versions.map(Version(fullPath, _)))
          )

        case None =>
          Directory(
            path = fullPath,
            name = name,
            children = None
          )
      }
    }
  }
}

private case class FileUploadedUpdate(path: String, versions: Seq[Version]) {
  def asJson: Json = {
    parseUnsafe(s"""{"path": "$path", "versions": ${versions.map(_.toJson).mkString("[", ", ", "]")}}""")
  }
}
