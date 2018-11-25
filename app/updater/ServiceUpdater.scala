package updater

import scala.sys.process._

sealed trait ServiceUpdater {
  def restartAndReplace(): Unit
}

class WindowsServiceUpdater extends ServiceUpdater {
  override def restartAndReplace(): Unit = ???
}

class LinuxServiceUpdater extends ServiceUpdater {
  override def restartAndReplace(): Unit = {
    "touch /tmp/update.txt" !
  }
}
