package updater

import better.files.File

import scala.language.postfixOps
import scala.sys.process._

sealed trait ServiceUpdater {
  def restartAndReplace(dirWithUpdate: File): Unit
}

class WindowsServiceUpdater extends ServiceUpdater {
  override def restartAndReplace(dirWithUpdate: File): Unit = {
    "%COMPSPEC% /C /D \"scripts\\restart_replace.cmd\" Arg1 Arg2 Arg3" !
  }
}

class LinuxServiceUpdater extends ServiceUpdater {
  override def restartAndReplace(dirWithUpdate: File): Unit = {
    "/bin/bash -c \"restart_replace.sh\"" !
  }
}
