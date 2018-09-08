package lib

import com.typesafe.scalalogging.StrictLogging
import lib.App._
import lib.clientapi.ClientStatus

class StateManager(cloudConnector: CloudConnector, dao: Dao, settings: Settings) extends StrictLogging {
  def appInit(): Result[Unit] = {
    pureResult(())
  }

  def login(sessionId: SessionId): Result[Unit] = {
    settings.sessionId(Option(sessionId))
  }

  def status: Result[ClientStatus] = {
    if (settings.initializing) {
      pureResult(ClientStatus.Initializing)
    } else {
      for {
        sessionId <- settings.sessionId
        status <- sessionId match {
          case Some(_) =>
            cloudConnector.status
              .map[ClientStatus] { _ =>
                logger.debug("Status READY")
                ClientStatus.Ready
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
}
