package lib

import java.net.URI
import java.nio.file.{FileSystems, Files, Paths}
import java.util

import com.typesafe.scalalogging.StrictLogging

import scala.collection.JavaConverters._

object WebpackBuildFile extends StrictLogging {

  private val bundleDir = if (Files.exists(Paths.get("public", "bundle"))) {
    Paths.get("public", "bundle")
  } else {
    val uri: URI = getClass.getClassLoader.getResource("public/bundle").toURI
    logger.debug(s"Found bundle resources @ $uri")

    val uriParts: Array[String] = uri.toString.split("!")
    val fs = FileSystems.newFileSystem(URI.create(uriParts.head), new util.HashMap[String, Object]())
    fs.getPath(uriParts(1))
  }

  logger.info(s"Locating built bundle at ${bundleDir.toUri}")

  val jsBundleName: String = {
    val packedFile = Files
      .list(bundleDir)
      .iterator()
      .asScala
      .filter(Files.isRegularFile(_))
      .toList
      .collectFirst {
        case f if f.toString.contains("js.bundle.") && !f.toString.contains("gz") => f
      }
      .getOrElse(sys.error("Could not locate JS bundle"))

    packedFile.getFileName.toString
  }

  val cssBundleName: String = {
    val packedFile = Files
      .list(bundleDir)
      .iterator()
      .asScala
      .filter(Files.isRegularFile(_))
      .toList
      .collectFirst {
        case f if f.toString.contains("style.bundle.") && !f.toString.contains("gz") => f
      }
      .getOrElse(sys.error("Could not locate styles bundle"))

    packedFile.getFileName.toString
  }

}
