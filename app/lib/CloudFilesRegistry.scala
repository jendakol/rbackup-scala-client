package lib

import java.util.concurrent.atomic.AtomicReference

import lib.App._
import lib.serverapi.RemoteFile

class CloudFilesRegistry(cloudFilesList: AtomicReference[CloudFilesList]) {

  private val lock = new Object

  // TODO connect to DB

  def updateFile(remoteFile: RemoteFile): Result[CloudFilesList] = lock.synchronized {
    pureResult {
      cloudFilesList.updateAndGet(_.update(remoteFile))
    }
  }

  def updateFilesList(cloudFilesList: CloudFilesList): Result[CloudFilesList] = lock.synchronized {
    this.cloudFilesList.set(cloudFilesList)
    pureResult(cloudFilesList)
  }

  def filesList: CloudFilesList = cloudFilesList.get()
}

object CloudFilesRegistry {
  def apply(cloudFilesList: CloudFilesList): CloudFilesRegistry = new CloudFilesRegistry(new AtomicReference(cloudFilesList))
}
