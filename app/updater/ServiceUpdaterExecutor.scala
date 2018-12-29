package updater

import better.files.File
import com.typesafe.scalalogging.StrictLogging
import lib.{AppVersion, DeviceId}

import scala.language.postfixOps

sealed trait ServiceUpdaterExecutor {
  def executeUpdate(currentVersion: AppVersion, newVersion: AppVersion, env: String, deviceId: DeviceId, dirWithUpdate: File): Unit
}

class WindowsServiceUpdaterExecutor extends ServiceUpdaterExecutor with StrictLogging {
  override def executeUpdate(currentVersion: AppVersion,
                             newVersion: AppVersion,
                             env: String,
                             deviceId: DeviceId,
                             dirWithUpdate: File): Unit = {
    logger.info(s"Starting the update with args: $currentVersion, $newVersion, $env, $deviceId, $dirWithUpdate")

    Runtime.getRuntime.exec(
      Array(
        "cmd",
        "/C",
        "start",
        "\"\"",
        "java",
        "-jar",
        "updater.jar",
        currentVersion.toString,
        newVersion.toString,
        env,
        deviceId.value,
        dirWithUpdate.pathAsString
      ))
    ()
  }
}

class LinuxServiceUpdaterExecutor extends ServiceUpdaterExecutor {
  override def executeUpdate(currentVersion: AppVersion,
                             newVersion: AppVersion,
                             env: String,
                             deviceId: DeviceId,
                             dirWithUpdate: File): Unit = {
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
