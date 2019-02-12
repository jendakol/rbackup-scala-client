package lib.server

import better.files.File
import cats.data.NonEmptyList
import cats.syntax.all._
import com.typesafe.scalalogging.StrictLogging
import controllers.WsApiController
import io.circe.Json
import io.circe.generic.extras.auto._
import javax.inject.Inject
import lib.App
import lib.App._
import lib.client.clientapi.FileTreeNode.{Directory, RegularFile}
import lib.client.clientapi.{FileTreeNode, Version}
import lib.db.{DbFile, FilesDao}
import lib.server.serverapi.{RemoteFile, RemoteFileVersion}

class CloudFilesRegistry @Inject()(wsApiController: WsApiController, dao: FilesDao) extends StrictLogging {

  def updateFile(file: File, remoteFile: RemoteFile): Result[Unit] = {
    dao.update(file, remoteFile)
  }

  def reportBackedUpFilesList: Result[Unit] = {
    def sendWsUpdate(json: Json): Result[Unit] = {
      wsApiController.send(controllers.WsMessage(`type` = "backedUpFilesUpdate", data = json), ignoreFailure = true)
    }

    ???

    //    for {
    //      files <- dao.listAll()
    //      fileTrees = FileTree.fromRemoteFiles(files.map(_.remoteFile))
    //      json = fileTrees.toJson
    //      _ <- sendWsUpdate(json)
    //    } yield {
    //      ()
    //    }
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
      reportBackedUpFilesList
  }

  def removeFileVersion(file: File, fileVersion: RemoteFileVersion): Result[Unit] = {
    App.leaveBreadcrumb("Removing file version", Map("path" -> file.pathAsString, "id" -> fileVersion.version))

    get(file).flatMap {
      case Some(f) =>
        val restOfVersions = f.versions.filterNot(_ == fileVersion)

        (if (restOfVersions.nonEmpty) {
           updateFile(file, f.copy(versions = restOfVersions))
         } else {
           removeFile(f)
         }) >> reportBackedUpFilesList

      case None =>
        App.leaveBreadcrumb("Didn't remove file version because it couldn't be found")
        pureResult(())
    }
  }

  def list(prefix: Option[String]): Result[List[FileTreeNode]] = {
    dao.listAll(prefix.getOrElse("")).map { files =>
      if (files.nonEmpty) {
        backedUpList(prefix, files)
      } else {
        List.empty
      }
    }
  }

  private[server] def backedUpList(prefix: Option[String], files: List[DbFile]): List[FileTreeNode] = {
    //noinspection MapGetOrElseBoolean
    def isFile(dbFile: DbFile): Boolean = {
      prefix
        .map(p => dbFile.path.stripPrefix(p).replace("\\", "/").contains("/"))
        .getOrElse(false)
    }

    files.map { dbFile =>
      import dbFile._

      if (isFile(dbFile)) {
        RegularFile(File(path), NonEmptyList.fromList(remoteFile.versions))
      } else {
        Directory(path, prefix)
      }
    }.distinct
  }
}

private case class FileUploadedUpdate(path: String, versions: Seq[Version]) {
  def asJson: Json = {
    parseUnsafe(s"""{"path": "$path", "versions": ${versions.map(_.toJson).mkString("[", ", ", "]")}}""")
  }
}
