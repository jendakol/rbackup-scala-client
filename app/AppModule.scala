import java.util.concurrent.Executors

import com.avast.metrics.scalaapi.Monitor
import com.typesafe.scalalogging.StrictLogging
import lib._
import lib.db.{Dao, DbScheme}
import lib.server.CloudConnector
import lib.settings.Settings
import monix.eval.Task
import monix.execution.Scheduler
import net.codingwell.scalaguice.ScalaModule
import org.apache.commons.lang3.SystemUtils
import org.http4s.Uri
import org.http4s.client.blaze.Http1Client
import play.api.{Configuration, Environment}
import scalikejdbc._
import scalikejdbc.config.DBs
import updater._
import utils.AllowedWsApiOrigins

import scala.collection.JavaConverters._
import scala.concurrent.ExecutionContext
import scala.concurrent.duration.Duration

class AppModule(environment: Environment, configuration: Configuration)
    extends ScalaModule
    with PropertiesConfiguration
    with StrictLogging {
  private val config = configuration.underlying

  DBs.setupAll()

  DB.autoCommit { implicit session =>
    DbScheme.create
  }

  override def configure(): Unit = {
    bindConfig(config.root(), "")(binder())

    val executorService = Executors.newCachedThreadPool()
    implicit val scheduler: Scheduler = Scheduler(
      executor = Executors.newScheduledThreadPool(4),
      ec = ExecutionContext.fromExecutorService(executorService)
    )

    val rootMonitor = Monitor.noOp() // TODO

    bind[AllowedWsApiOrigins].toInstance(AllowedWsApiOrigins(config.getStringList("allowedWsApiOrigins").asScala))

    val cloudConnector = CloudConnector.fromConfig(config.getConfig("cloudConnector"))
    val dao = new Dao(executorService)
    val settings = new Settings(dao)
    val stateManager = new StateManager(DeviceId(config.getString("deviceId")), cloudConnector, dao, settings)

    bind[CloudConnector].toInstance(cloudConnector)
    bind[Dao].toInstance(dao)
    bind[Settings].toInstance(settings)
    bind[StateManager].toInstance(stateManager)

    bind[GithubConnector].toInstance {
      new GithubConnector(Http1Client[Task]().runSyncUnsafe(Duration.Inf),
                          Uri.unsafeFromString("http://localhost:80/releases.json"),
                          AppVersion(0, 1, 0))
    }

    bind[Monitor].annotatedWithName("FilesHandler").toInstance(rootMonitor.named("fileshandler"))

    bind[Scheduler].toInstance(scheduler)

    bindServiceUpdater()
    bind[App].asEagerSingleton()

    dao.resetProcessingFlags().value.runSyncUnsafe(Duration.Inf)

    // startup:

    stateManager.appInit().value.toIO.unsafeRunSync() match {
      case Right(_) => settings.initializing(false)
      case Left(err) => throw err
    }
  }

  private def bindServiceUpdater(): Unit = {
    val updater = if (SystemUtils.IS_OS_WINDOWS) {
      new WindowsServiceUpdater
    } else new LinuxServiceUpdater

    bind[ServiceUpdater].toInstance(updater)
  }
}
