package lib.commands

import better.files.File
import cats.data.EitherT
import com.typesafe.scalalogging.StrictLogging
import controllers._
import io.circe.Json
import io.circe.generic.extras.auto._
import io.circe.syntax._
import javax.inject.{Inject, Singleton}
import lib.App.{failedResult, parseUnsafe, DateTimeFormatter, JsonSuccess, Result}
import lib.AppException.LoginRequired
import lib.client.clientapi.{BackupSetNode, FileTree}
import lib.db.Dao
import lib.settings.Settings
import lib.{App, AppException, ServerSession, _}
import monix.eval.Task
import utils.CirceImplicits._

@Singleton
class BackupCommandExecutor @Inject()(dao: Dao,
                                      backupSetsExecutor: BackupSetsExecutor,
                                      wsApiController: WsApiController,
                                      settings: Settings)
    extends StrictLogging {
  def execute(command: BackupCommand): Result[Json] = command match {

    case BackedUpFileListCommand =>
      App.leaveBreadcrumb("Getting backed-up files list")
      backedUpList

    case BackupSetsListCommand =>
      App.leaveBreadcrumb("Listing backup sets")

      dao.listAllBackupSets().map { bss =>
        val sets = bss.map { bs =>
          val lastTime = bs.lastExecution.map(DateTimeFormatter.format).getOrElse("never")
          val nextTime = bs.lastExecution.map(_.plus(bs.frequency)).map(DateTimeFormatter.format).getOrElse("soon")

          parseUnsafe(
            s"""{ "id": ${bs.id}, "name":"${bs.name}", "processing": ${bs.processing}, "last_execution": "$lastTime", "next_execution": "$nextTime" }""")
        }

        parseUnsafe(s"""{"success": true, "data": [${sets.mkString(",")}]}""")
      }

    case BackupSetDetailsCommand(id) =>
      App.leaveBreadcrumb("Requesting backup set detail", Map("id" -> id))

      dao.listFilesInBackupSet(id).map { files =>
        parseUnsafe(s"""{"success": true, "data": {"files": ${files.map(_.pathAsString).asJson}}}""")
      }

    case BackupSetExecuteCommand(id) =>
      App.leaveBreadcrumb("Requesting backup set execution", Map("id" -> id))

      withSession { implicit session =>
        for {
          bs <- dao.getBackupSet(id)
          _ <- backupSetsExecutor.execute(bs.getOrElse(throw new IllegalArgumentException("Backup set not found"))) // TODO
        } yield {
          JsonSuccess
        }
      }

    case BackupSetFilesUpdateCommand(id, files) =>
      App.leaveBreadcrumb("Updating backup set files", Map("files" -> files))

      for {
        _ <- updateBackupSetFilesList(id, files)
        currentFiles <- dao.listFilesInBackupSet(id)
        _ <- wsApiController.send(
          "backupSetDetailsUpdate",
          parseUnsafe(s"""{ "id": $id, "type": "files", "files":${currentFiles.map(_.pathAsString).asJson}}"""),
          ignoreFailure = true
        )
      } yield JsonSuccess
  }

  private def backedUpList: EitherT[Task, AppException, Json] = {
    dao.listAllFiles.map { files =>
      val fileTrees = FileTree.fromRemoteFiles(files.map(_.remoteFile))

      logger.debug(s"Backed-up file trees: $fileTrees")

      val nonEmptyTrees = fileTrees.filterNot(_.isEmpty)

      if (nonEmptyTrees.nonEmpty) {
        logger.trace {
          val allFiles = nonEmptyTrees
            .collect {
              case ft @ FileTree(_, Some(_)) => ft.allFiles
              case _ => None
            }
            .flatten
            .flatMap(_.toList)

          s"Returning list of ${allFiles.length} backed-up files"
        }

        nonEmptyTrees.map(_.toJson).asJson
      } else {
        logger.debug("Returning empty list of backed-up files")
        parseUnsafe {
          s"""[{"icon": "fas fa-info-circle", "isLeaf": true, "opened": false, "value": "_", "text": "No backed-up files yet", "isFile": false, "isVersion": false, "isDir": false}]"""
        }
      }
    }
  }

  private def updateBackupSetFilesList(bsId: Long, files: Seq[BackupSetNode]): Result[Unit] = {
    val normalized = files.flatMap(_.flattenNormalize)

    dao.updateFilesInBackupSet(bsId, normalized.map(n => File(n.value)))
  }

  private def withSession[A](f: ServerSession => Result[A]): Result[A] = {
    settings.session.flatMap {
      case Some(sid) => f(sid)
      case None => failedResult(LoginRequired())
    }
  }
}
