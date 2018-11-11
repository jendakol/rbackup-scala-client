package lib

import better.files.File
import controllers.WsApiController
import io.circe.Json
import io.circe.generic.extras.auto._
import javax.inject.Inject
import lib.App._
import lib.CirceImplicits._
import lib.clientapi.{FileTree, Version}
import lib.serverapi.{RemoteFile, RemoteFileVersion}

class CloudFilesRegistry @Inject()(wsApiController: WsApiController, dao: Dao) {

  def updateFile(file: File, remoteFile: RemoteFile): Result[Unit] = {
    dao.updateFile(file, remoteFile)
  }

  def reportBackedUpFilesList: Result[Unit] = {
    def sendWsUpdate(json: Json): Result[Unit] = {
      wsApiController.send {
        controllers.WsMessage(
          `type` = "backedUpFilesUpdate",
          data = json
        )
      }
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
}

private case class FileUploadedUpdate(path: String, versions: Seq[Version]) {
  def asJson: Json = {
    parseUnsafe(s"""{"path": "$path", "versions": ${versions.map(_.toJson).mkString("[", ", ", "]")}}""")
  }
}
