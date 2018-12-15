package lib.server

import better.files.File
import cats.syntax.all._
import controllers.WsApiController
import io.circe.Json
import io.circe.generic.extras.auto._
import javax.inject.Inject
import lib.App
import lib.App._
import lib.client.clientapi.{FileTree, Version}
import lib.db.Dao
import lib.server.serverapi.{RemoteFile, RemoteFileVersion}

class CloudFilesRegistry @Inject()(wsApiController: WsApiController, dao: Dao) {

  def updateFile(file: File, remoteFile: RemoteFile): Result[Unit] = {
    dao.updateFile(file, remoteFile)
  }

  def reportBackedUpFilesList: Result[Unit] = {
    def sendWsUpdate(json: Json): Result[Unit] = {
      wsApiController.send(controllers.WsMessage(`type` = "backedUpFilesUpdate", data = json), ignoreFailure = true)
    }

    for {
      files <- dao.listAllFiles
      fileTrees = FileTree.fromRemoteFiles(files.map(_.remoteFile))
      json = fileTrees.toJson
      _ <- sendWsUpdate(json)
    } yield {
      ()
    }
  }

  def versions(file: File): Result[Option[Vector[RemoteFileVersion]]] = {
    dao.getFile(file).map(_.map(_.remoteFile.versions))
  }

  def get(file: File): Result[Option[RemoteFile]] = {
    dao.getFile(file).map(_.map(_.remoteFile))
  }

  def removeFile(file: RemoteFile): Result[Unit] = {
    App.leaveBreadcrumb("Removing file", Map("path" -> file.originalName))

    dao.deleteFile(file) >>
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
}

private case class FileUploadedUpdate(path: String, versions: Seq[Version]) {
  def asJson: Json = {
    parseUnsafe(s"""{"path": "$path", "versions": ${versions.map(_.toJson).mkString("[", ", ", "]")}}""")
  }
}
