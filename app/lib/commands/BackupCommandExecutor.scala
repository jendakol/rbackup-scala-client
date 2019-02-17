package lib.commands

import java.time.Duration

import better.files.File
import cats.data.EitherT
import com.typesafe.scalalogging.StrictLogging
import controllers._
import io.circe.Json
import io.circe.generic.extras.auto._
import io.circe.syntax._
import javax.inject.{Inject, Singleton}
import lib.App._
import lib.AppException.{InvalidArgument, LoginRequired}
import lib.db.{BackupSet, BackupSetsDao, FilesDao}
import lib.server.CloudFilesRegistry
import lib.settings.Settings
import lib.{App, AppException, ServerSession, _}
import monix.eval.Task
import utils.CirceImplicits._

@Singleton
class BackupCommandExecutor @Inject()(bsDao: BackupSetsDao,
                                      filesDao: FilesDao,
                                      filesRegistry: CloudFilesRegistry,
                                      backupSetsExecutor: BackupSetsExecutor,
                                      wsApiController: WsApiController,
                                      settings: Settings)
    extends StrictLogging {
  def execute(command: BackupCommand): Result[Json] = command match {

    case BackedUpFileListCommand(prefix) =>
      App.leaveBreadcrumb("Getting backed-up files list")
      backedUpList(prefix)

    case BackupSetsListCommand =>
      App.leaveBreadcrumb("Listing backup sets")

      bsDao.listAll().map { bss =>
        val sets = bss.map(toJson)

        parseUnsafe(s"""{"success": true, "data": [${sets.mkString(",")}]}""")
      }

    case BackupSetDetailsCommand(id) =>
      App.leaveBreadcrumb("Requesting backup set detail", Map("id" -> id))

      bsDao.listFiles(id).map { files =>
        parseUnsafe(s"""{"success": true, "data": {"files": ${files.map(_.pathAsString).asJson}}}""")
      }

    case BackupSetExecuteCommand(id) =>
      App.leaveBreadcrumb("Requesting backup set execution", Map("id" -> id))

      withSession { implicit session =>
        for {
          bs <- bsDao.get(id)
          _ <- backupSetsExecutor.execute(bs.getOrElse(throw new IllegalArgumentException("Backup set not found"))) // TODO
        } yield {
          JsonSuccess
        }
      }

    case BackupSetFilesUpdateCommand(id, files) =>
      App.leaveBreadcrumb("Updating backup set files", Map("id" -> id, "files" -> files))

      for {
        _ <- updateBackupSetFilesList(id, files)
        currentFiles <- bsDao.listFiles(id)
        _ <- wsApiController.send(
          "backupSetDetailsUpdate",
          parseUnsafe(s"""{ "id": $id, "type": "files", "files":${currentFiles.map(_.pathAsString).asJson}}"""),
          ignoreFailure = true
        )
      } yield JsonSuccess

    case BackupSetFrequencyUpdateCommand(id, freqMinutes) =>
      App.leaveBreadcrumb("Updating backup set frequency", Map("id" -> id, "minutes" -> freqMinutes))

      bsDao.get(id).flatMap {
        case Some(bs) =>
          val updated = bs.copy(frequency = Duration.ofMinutes(freqMinutes))

          bsDao
            .update(updated)
            .map { _ =>
              val nextTime = updated.lastExecution.map(_.plus(updated.frequency)).map(DateTimeFormatter.format).getOrElse("soon")
              parseUnsafe(s"""{ "success": true, "data": {"next_execution": "$nextTime"} }""")
            }

        case None =>
          failedResult(InvalidArgument(s"Could not finx backup set with ID $id"))
      }

    case BackupSetNewCommand(name) =>
      App.leaveBreadcrumb("Creating backup set", Map("name" -> name))
      bsDao.create(name).mapToJsonSuccess

    case BackupSetDeleteCommand(id) =>
      App.leaveBreadcrumb("Deleting backup set", Map("id" -> id))
      bsDao.delete(id).mapToJsonSuccess
  }

  private def toJson(bs: BackupSet): Json = {
    val lastTime = bs.lastExecution.map(DateTimeFormatter.format).getOrElse("never")
    val nextTime = bs.lastExecution.map(_.plus(bs.frequency)).map(DateTimeFormatter.format).getOrElse("soon")

    parseUnsafe {
      s"""{ "id": ${bs.id}, "name":"${bs.name}", "processing": ${bs.processing}, "last_execution": "$lastTime", "next_execution": "$nextTime", "frequency": ${bs.frequency.toMinutes} }"""
    }
  }

  private def backedUpList(prefix: Option[String]): EitherT[Task, AppException, Json] = {
    filesRegistry.listFiles(prefix).map { nodes =>
      App.leaveBreadcrumb("Getting backed-up list", Map("prefix" -> prefix.getOrElse("")))

      if (nodes.nonEmpty) {
        nodes.map(_.toJson).asJson
      } else {
        if (prefix.isEmpty) {
          logger.debug("Returning empty list of backed-up files")
          parseUnsafe {
            s"""[{"icon": "fas fa-info-circle", "isLeaf": true, "opened": false, "value": "_", "text": "No backed-up files yet", "isFile": false, "isVersion": false, "isDir": false}]"""
          }
        } else {
          logger.error("Didn't find any files with non-empty prefix, returning empty JSON")
          parseUnsafe(s"[]")
        }
      }
    }
  }

  // TODO: corner case - what to do with files from other platforms??
  //  private def isOtherPlatformFile(f: FileTreeNode): Boolean = {
  //    if (SystemUtils.IS_OS_WINDOWS) {
  //      f.path.headOption.contains('/')
  //    } else if (SystemUtils.IS_OS_UNIX) {
  //      f.path.headOption.exists(_ != '/')
  //    } else sys.error("Unsupported platform")
  //  }

  private def updateBackupSetFilesList(bsId: Long, paths: Seq[String]): Result[Unit] = {
    val normalized = normalizePaths(paths)

    logger.debug(s"Updating files in backup set $bsId: ${normalized.mkString("[", ", ", "]")}")
    bsDao.updateFiles(bsId, normalized.map(File(_)))
  }

  private def normalizePaths(paths: Seq[String]): Seq[String] = {
    paths
      .filter { path => // filter out files which are already present vie their parents
        !paths.exists(p => p != path && path.startsWith(p))
      }
  }

  private def withSession[A](f: ServerSession => Result[A]): Result[A] = {
    settings.session.flatMap {
      case Some(sid) => f(sid)
      case None => failedResult(LoginRequired())
    }
  }
}
