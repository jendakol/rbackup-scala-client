package updater

import better.files.File
import com.typesafe.scalalogging.StrictLogging

import scala.language.postfixOps

sealed trait ServiceUpdater {
  def restartAndReplace(dirWithUpdate: File): Unit
}

class WindowsServiceUpdater extends ServiceUpdater with StrictLogging {
  override def restartAndReplace(dirWithUpdate: File): Unit = {
    Runtime.getRuntime.exec(
      Array(
        "cmd",
        "/C",
        "start",
        "\"\"",
        "restart_replace.cmd",
        dirWithUpdate.pathAsString
      ))
    ()
  }
}

class LinuxServiceUpdater extends ServiceUpdater {
  override def restartAndReplace(dirWithUpdate: File): Unit = {
    Runtime.getRuntime.exec(
      Array(
        "/bin/bash",
        "-c",
        "restart_replace.sh",
        dirWithUpdate.pathAsString,
        "&",
      ))
    ()
  }
}
