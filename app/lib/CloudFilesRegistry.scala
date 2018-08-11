package lib

import java.util.concurrent.atomic.AtomicReference

import controllers.WsApiController
import io.circe.Json
import io.circe.generic.extras.auto._
import javax.inject.Inject
import lib.App._
import lib.CirceImplicits._
import lib.clientapi.FileTreeNode.FileVersion
import lib.serverapi.RemoteFile

class CloudFilesRegistry @Inject()(wsApiController: WsApiController) {

  private val lock = new Object

  private val cloudFilesList: AtomicReference[CloudFilesList] = new AtomicReference(CloudFilesList(Nil))

  // TODO connect to DB

  def updateFile(remoteFile: RemoteFile): Result[CloudFilesList] = lock.synchronized {
    val newList = cloudFilesList.updateAndGet(_.update(remoteFile))

    wsApiController
      .send(
        controllers.WsMessage(
          "fileTreeUpdate",
          FileTreeUpdate(remoteFile.originalName, remoteFile.versions.map(FileVersion(_))).asJson
        ))
      .map(_ => newList)
  }

  def updateFilesList(cloudFilesList: CloudFilesList): Result[CloudFilesList] = lock.synchronized {
    this.cloudFilesList.set(cloudFilesList)
    pureResult(cloudFilesList)
  }

  def filesList: CloudFilesList = cloudFilesList.get()
}

private case class FileTreeUpdate(path: String, versions: Seq[FileVersion]) {
  def asJson: Json = {
    parseSafe(s"""{"path": "$path", "versions": ${versions.map(_.toJson).mkString("[", ", ", "]")}}""")
  }
}
