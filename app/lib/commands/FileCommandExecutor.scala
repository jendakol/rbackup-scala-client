package lib.commands

import better.files.File
import com.typesafe.scalalogging.StrictLogging
import controllers._
import io.circe.Json
import io.circe.generic.extras.auto._
import io.circe.syntax._
import javax.inject.Inject
import lib.App._
import lib.AppException.{InvalidArgument, LoginRequired}
import lib._
import lib.server.CloudFilesRegistry
import lib.server.serverapi._
import lib.settings.Settings
import utils.CirceImplicits._

class FileCommandExecutor @Inject()(wsApiController: WsApiController,
                                    tasksManager: TasksManager,
                                    filesHandler: FilesHandler,
                                    filesRegistry: CloudFilesRegistry,
                                    settings: Settings)
    extends StrictLogging {
  def execute(command: FileCommand): Result[Json] = command match {

    case UploadCommand(path) =>
      App.leaveBreadcrumb("Manual upload", Map("path" -> path))

      withSession { implicit session =>
        val file = File(path)

        // TODO check file exists

        uploadManually(file)
      }

    case DownloadCommand(path, versionId) =>
      App.leaveBreadcrumb("Download", Map("path" -> path, "versionId" -> versionId))
      logger.debug(s"Downloading $path with versionId $versionId")

      withSession { implicit session =>
        download(File(path), versionId)
      }

    case RemoveRemoteFileVersion(path, versionId) =>
      val file = File(path)

      withSession { implicit session =>
        for {
          versions <- filesRegistry.versions(file)
          version = versions
            .flatMap(_.find(_.version == versionId))
            .getOrElse(throw InvalidArgument("Could not delete non-existing file version"))
          _ <- filesHandler.removeFileVersion(version)
          _ <- filesRegistry.removeFileVersion(file, version)
        } yield {
          JsonSuccess
        }
      }

    case RemoveRemoteFile(path) =>
      val file = File(path)

      withSession { implicit session =>
        for {
          fileOpt <- filesRegistry.get(file)
          file = fileOpt.getOrElse(throw InvalidArgument("Could not delete non-existing file"))
          _ <- filesHandler.removeFile(file)
          _ <- filesRegistry.removeFile(file)
        } yield {
          JsonSuccess
        }
      }
  }

  private def uploadManually(file: File)(implicit ss: ServerSession): Result[Json] = {
    def reportResult(results: List[Option[UploadResponse]]): Result[Unit] = {
      val respJson = if (results.size == 1) {
        results.head match {
          case None => parseUnsafe(s"""{ "success": true, "path": ${file.pathAsString.asJson} }""")
          case Some(UploadResponse.Uploaded(_)) => parseUnsafe(s"""{ "success": true, "path": ${file.pathAsString.asJson} }""")
          case Some(UploadResponse.Sha256Mismatch) =>
            parseUnsafe(s"""{ "success": false, "path": ${file.pathAsString.asJson} "reason": "SHA-256 mismatch" }""")
        }
      } else {
        val failures = results.collect {
          case Some(UploadResponse.Sha256Mismatch) => "Could not upload file" // TODO this is sad
        }

        if (failures.nonEmpty) {
          parseUnsafe(s"""{ "success": false, "path": ${file.pathAsString.asJson}, "reason": "${failures.mkString("[", ", ", "]")}" }""")
        } else {
          parseUnsafe(s"""{ "success": true, "path": ${file.pathAsString.asJson}}""")
        }
      }

      wsApiController.send("finishUpload", respJson, ignoreFailure = true)
    }

    val runningTask = if (file.isDirectory) {
      RunningTask.DirUpload(file.pathAsString)
    } else {
      RunningTask.FileUpload(file.pathAsString)
    }

    tasksManager
      .start(runningTask) {
        for {
          results <- filesHandler.upload(file)
          _ <- filesRegistry.reportBackedUpFilesList
          _ <- reportResult(results)
        } yield ()
      }
      .mapToJsonSuccess
  }

  /*
   * .recover {
              case AppException.AccessDenied(_, _) =>
                parseSafe(s"""{ "success": false, "path": ${file.pathAsString.asJson}, "reason": "Access denied" }""")
              case AppException.ServerNotResponding(_) =>
                parseSafe(s"""{ "success": false, "path": ${file.pathAsString.asJson}, "reason": "Server does not respond" }""")
            }
   * */

  private def download(file: File, versionId: Long)(implicit ss: ServerSession): Result[Json] = {
    def reportResult(fileVersion: RemoteFileVersion)(results: List[DownloadResponse]): Result[Unit] = {
      val respJson = if (results.size == 1) {
        results.head match {
          case DownloadResponse.Downloaded(_, _) =>
            parseUnsafe(
              s"""{ "success": true, "path": ${file.pathAsString.asJson}, "time": "${DateTimeFormatter.format(fileVersion.created)}" }""")
          case DownloadResponse.FileVersionNotFound(_) =>
            parseUnsafe(s"""{ "success": false,, "path": ${file.pathAsString.asJson} "reason": "Version not found" }""")
        }
      } else {
        val failures = results.collect {
          case DownloadResponse.FileVersionNotFound(_) => "Version not found" // TODO this is weird
        }

        if (failures.nonEmpty) {
          parseUnsafe(s"""{ "success": false, "path": ${file.pathAsString.asJson}, "reason": "${failures.mkString("[", ", ", "]")}" }""")
        } else {
          parseUnsafe(s"""{ "success": true, "path": ${file.pathAsString.asJson}}""")
        }
      }

      wsApiController.send("finishDownload", respJson, ignoreFailure = true)
    }

    val downloadTask: Result[Unit] = filesRegistry
      .get(file)
      .map(_.flatMap { file =>
        file.versions.collectFirst {
          case fv if fv.version == versionId => file -> fv
        }
      })
      .flatMap {
        case Some((remoteFile, fileVersion)) =>
          filesHandler
            .download(remoteFile, fileVersion, File(remoteFile.originalName))
            .flatMap(reportResult(fileVersion))

        case None =>
          wsApiController.send("finishDownload", parseUnsafe {
            s"""{ "success": false, "path": ${file.pathAsString.asJson}, "reason": "Version not found" }"""
          }, ignoreFailure = true)
      }
      .recoverWith {
        case AppException.AccessDenied(_, _) =>
          wsApiController.send(
            `type` = "finishDownload",
            data = parseUnsafe {
              s"""{ "success": false, "path": ${file.pathAsString.asJson}, "reason": "Access to the file was denied" }"""
            },
            ignoreFailure = true
          )
      }

    tasksManager
      .start(RunningTask.FileDownload(file.pathAsString))(downloadTask)
      .mapToJsonSuccess
  }

  private def withSession[A](f: ServerSession => Result[A]): Result[A] = {
    settings.session.flatMap {
      case Some(sid) => f(sid)
      case None => failedResult(LoginRequired())
    }
  }
}
