import java.lang.{Boolean => JBoolean}

import com.google.inject.{AbstractModule, Binder, Key}
import com.typesafe.config._
import utils.{ConfigProperty, ConfigPropertyImpl}

import scala.collection.JavaConverters._

trait PropertiesConfiguration extends AbstractModule {
  // based on: http://vastdevblog.vast.com/blog/2012/06/16/creating-named-guice-bindings-for-typesafe-config-properties/
  protected def bindConfig(obj: ConfigValue, bindingPath: String)(implicit binder: Binder): Unit = obj.valueType() match {
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
