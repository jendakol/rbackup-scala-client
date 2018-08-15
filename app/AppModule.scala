import java.lang.{Boolean => JBoolean}
import java.util.concurrent.Executors

import com.google.inject.{AbstractModule, Binder, Key}
import com.typesafe.config._
import com.typesafe.scalalogging.StrictLogging
import lib.{CloudConnector, Dao, DbScheme, Settings}
import monix.execution.Scheduler
import monix.execution.schedulers.SchedulerService
import net.codingwell.scalaguice.ScalaModule
import scalikejdbc._
import scalikejdbc.config.DBs
import utils.{AllowedApiOrigins, ConfigProperty, ConfigPropertyImpl}

import scala.collection.JavaConverters._
import scala.concurrent.Await
import scala.concurrent.duration.Duration

class AppModule extends AbstractModule with ScalaModule with StrictLogging {
  private lazy val config = ConfigFactory.load()

  DBs.setupAll()

  DB.autoCommit { implicit session =>
    DbScheme.create
  }

  override def configure(): Unit = {
    bindConfig(config.root(), "")(binder())

    val executorService = Executors.newCachedThreadPool()
    implicit val scheduler: SchedulerService = Scheduler(executorService) // TODO

    bind[AllowedApiOrigins].toInstance(AllowedApiOrigins(config.getStringList("allowedWsApiOrigins").asScala))

    val cloudConnector = CloudConnector.fromConfig(config.getConfig("cloudConnector"))
    val dao = new Dao(executorService)
    val settings = new Settings(dao)

    bind[CloudConnector].toInstance(cloudConnector)
    bind[Dao].toInstance(dao)
    bind[Settings].toInstance(settings)

    bind[Scheduler].toInstance(scheduler)

    // startup:
    val deviceId = config.getString("deviceId")

    // load files from server, compare with DB

    settings.initializing(false)

//    for {
//      list <- cloudConnector.listFiles(Some(deviceId))
//    }
  }

  // based on: http://vastdevblog.vast.com/blog/2012/06/16/creating-named-guice-bindings-for-typesafe-config-properties/
  private def bindConfig(obj: ConfigValue, bindingPath: String)(implicit binder: Binder): Unit = obj.valueType() match {
    case ConfigValueType.OBJECT =>
      val configObj = obj.asInstanceOf[ConfigObject]
      // Bind the config from the object.
      binder
        .bind(Key.get(classOf[Config], initProperty(bindingPath)))
        .toInstance(configObj.toConfig)
      // Bind any nested values.
      configObj
        .entrySet()
        .asScala
        .foreach { me =>
          val key = me.getKey
          bindConfig(me.getValue, bindingPath + "." + key)
        }
    case ConfigValueType.LIST =>
      val values = obj.asInstanceOf[ConfigList]
      for (i <- 0 until values.size()) {
        val configValue = values.asScala(i)
        bindConfig(configValue, bindingPath + "." + i.toString)
      }
    case ConfigValueType.NUMBER =>
      // Bind as string and rely on guice's conversion code when the value is used.
      binder
        .bindConstant()
        .annotatedWith(initProperty(bindingPath))
        .to(
          obj
            .unwrapped()
            .asInstanceOf[Number]
            .toString)
    case ConfigValueType.BOOLEAN =>
      binder
        .bindConstant()
        .annotatedWith(initProperty(bindingPath))
        .to(
          obj
            .unwrapped()
            .asInstanceOf[JBoolean])
    case ConfigValueType.NULL =>
    // NULL values are ignored.
    case ConfigValueType.STRING =>
      binder
        .bindConstant()
        .annotatedWith(initProperty(bindingPath))
        .to(
          obj
            .unwrapped()
            .asInstanceOf[String])
  }

  private def initProperty(name: String): ConfigProperty =
    new ConfigPropertyImpl(if (!name.isEmpty) name.substring(1) else "root")
}
