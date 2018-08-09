package lib

import better.files.File
import lib.serverapi.{FileVersion, RemoteFile}

case class CloudFilesList private (files: Map[String, RemoteFile]) {
  def versions(file: File): Option[Vector[FileVersion]] = {
    files.get(file.path.toAbsolutePath.toString).map(_.versions)
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
