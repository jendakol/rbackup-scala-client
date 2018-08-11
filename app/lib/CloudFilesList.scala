package lib

import better.files.File
import lib.serverapi.{RemoteFile, RemoteFileVersion}

case class CloudFilesList private (files: Map[String, RemoteFile]) {
  def versions(file: File): Option[Vector[RemoteFileVersion]] = {
    files.get(file.path.toAbsolutePath.toString).map(_.versions)
  }

  def get(file: File): Option[RemoteFile] = {
    files.get(file.path.toAbsolutePath.toString)
  }

  def update(remoteFile: RemoteFile): CloudFilesList = {
    copy(
      files = files + (remoteFile.originalName -> remoteFile)
    )
  }
}

object CloudFilesList {
  def apply(files: Seq[RemoteFile]): CloudFilesList = {
    val map = files.map(f => f.originalName -> f).toMap

    new CloudFilesList(map)
  }
}
