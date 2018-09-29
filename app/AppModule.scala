import java.util.concurrent.Executors

import com.avast.metrics.scalaapi.Monitor
import com.typesafe.config._
import com.typesafe.scalalogging.StrictLogging
import lib._
import monix.execution.Scheduler
import monix.execution.schedulers.SchedulerService
import net.codingwell.scalaguice.ScalaModule
import scalikejdbc._
import scalikejdbc.config.DBs
import utils.AllowedApiOrigins

import scala.collection.JavaConverters._

class AppModule extends ScalaModule with PropertiesConfiguration with StrictLogging {
  private lazy val config = ConfigFactory.load()

  DBs.setupAll()

  DB.autoCommit { implicit session =>
    DbScheme.create
  }

  override def configure(): Unit = {
    bindConfig(config.root(), "")(binder())

    val executorService = Executors.newCachedThreadPool()
    implicit val scheduler: SchedulerService = Scheduler(executorService) // TODO

    val rootMonitor = Monitor.noOp()

    bind[AllowedApiOrigins].toInstance(AllowedApiOrigins(config.getStringList("allowedWsApiOrigins").asScala))

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

    // startup:

    stateManager.appInit().value.toIO.unsafeRunSync() match {
      case Right(_) => settings.initializing(false)
      case Left(err) => throw err
    }
  }

}
