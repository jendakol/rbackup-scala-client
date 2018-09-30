package lib

import cats.Traverse
import cats.instances.list._
import com.typesafe.scalalogging.StrictLogging
import lib.App._
import lib.clientapi.ClientStatus
import lib.serverapi.ListFilesResponse

class StateManager(deviceId: DeviceId, cloudConnector: CloudConnector, dao: Dao, settings: Settings) extends StrictLogging {
  def appInit(): Result[Unit] = {
    settings.session.flatMap {
      case Some(sessionId) => downloadRemoteFilesList(sessionId)
      case None => pureResult(())
    }
  }

  def login(implicit session: ServerSession): Result[Unit] = {
    for {
      _ <- settings.session(Option(session))
      _ <- downloadRemoteFilesList
    } yield {
      ()
    }
  }

  def status: Result[ClientStatus] = {
    if (settings.initializing) {
      pureResult(ClientStatus.Initializing)
    } else {
      for {
        session <- settings.session
        status <- session match {
          case Some(ss) =>
            cloudConnector
              .status(ss)
              .map[ClientStatus] { _ =>
                logger.debug("Status READY")
                ClientStatus.Ready(ss.host)
              }
              .recover {
                case e =>
                  logger.debug("Server not available - status DISCONNECTED", e)
                  ClientStatus.Disconnected
              }

          case None =>
            logger.debug("Session ID not available - status INSTALLED")
            pureResult(ClientStatus.Installed)
        }
      } yield status
    }
  }

  def downloadRemoteFilesList(implicit session: ServerSession): Result[Unit] = {
    logger.debug("Downloading remote files list")

    for {
      allFiles <- cloudConnector.listFiles(Some(deviceId)).subflatMap {
        case ListFilesResponse.FilesList(files) =>
          logger.debug(s"Downloaded files list with ${files.length} items")
          Right(files)
        case ListFilesResponse.DeviceNotFound(_) =>
          logger.error("Server does not know this device even though the request was authenticated")
          Left(AppException.Unauthorized)
      }
      _ <- dao.deleteAllFiles()
      _ <- Traverse[List].sequence(allFiles.map(dao.saveRemoteFile).toList)
    } yield {
      logger.info(s"Downloaded ${allFiles.length} remote files")
      ()
    }
  }
}
