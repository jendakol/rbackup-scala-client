import java.util.concurrent.Executors

import com.avast.metrics.scalaapi.Monitor
import com.typesafe.scalalogging.StrictLogging
import lib._
import monix.execution.Scheduler
import net.codingwell.scalaguice.ScalaModule
import play.api.{Configuration, Environment}
import scalikejdbc._
import scalikejdbc.config.DBs
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

    bind[Monitor].annotatedWithName("FilesHandler").toInstance(rootMonitor.named("fileshandler"))

    bind[Scheduler].toInstance(scheduler)

    bind[App].asEagerSingleton()

    dao.resetProcessingFlags().value.runSyncUnsafe(Duration.Inf)

    // startup:

    stateManager.appInit().value.toIO.unsafeRunSync() match {
      case Right(_) => settings.initializing(false)
      case Left(err) => throw err
    }
  }

}
