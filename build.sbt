import com.typesafe.sbt.packager.MappingsHelper._

import scala.sys.process.Process

lazy val Versions = new {
  val monix = "3.0.0-RC1"
  val http4s = "0.18.21"
  val scalikeJdbc = "3.3.1"
  val metricsVersion = "2.4.4"
}

mappings in Universal ++= directory(baseDirectory.value / "public")

name := "rbackup-client"

version := sys.env.getOrElse("VERSION", "0.1.0")

scalaVersion := "2.12.7"

lazy val `rbackup-client` = (project in file(".")).enablePlugins(PlayScala)

libraryDependencies ++= Seq(guice, filters, jdbc, cacheApi, ws)

libraryDependencies ++= Seq(
  "org.http4s" %% "http4s-dsl" % Versions.http4s,
  "org.http4s" %% "http4s-blaze-client" % Versions.http4s,
  "io.monix" %% "monix" % Versions.monix,
  "com.h2database" % "h2" % "1.4.197",
  "org.scalikejdbc" %% "scalikejdbc" % Versions.scalikeJdbc,
  "org.scalikejdbc" %% "scalikejdbc-config" % Versions.scalikeJdbc,
  "com.github.pathikrit" %% "better-files" % "3.6.0",
  "commons-io" % "commons-io" % "2.6",
  "com.github.pureconfig" %% "pureconfig" % "0.9.1",
  "com.github.pureconfig" %% "pureconfig-http4s" % "0.9.1",
  "net.codingwell" %% "scala-guice" % "4.1.1",
  "com.dripower" %% "play-circe" % "2610.0",
  "io.circe" %% "circe-generic-extras" % "0.10.1",
  "com.avast.metrics" %% "metrics-scala" % Versions.metricsVersion,
  "com.avast.metrics" % "metrics-statsd" % Versions.metricsVersion,
  "com.softwaremill.sttp" %% "core" % "1.5.1",
  "org.apache.commons" % "commons-lang3" % "3.8.1",
  "com.github.pathikrit" %% "better-files" % "3.6.0",
  "org.typelevel" %% "cats-core" % "1.5.0",
  "io.sentry" % "sentry-logback" % "1.7.14",
  "com.typesafe.scala-logging" %% "scala-logging" % "3.9.0",
  "org.scalatest" %% "scalatest" % "3.0.5",
  "org.mockito" % "mockito-core" % "2.23.0" % "test"
)

libraryDependencies ~= {
  //noinspection UnnecessaryPartialFunction
  _ map {
    case m =>
      m.exclude("commons-logging", "commons-logging")
        .exclude("com.typesafe.play", "sbt-link")
  }
}

// Play framework hooks for development
PlayKeys.playRunHooks += WebpackServer(file("./front"))

unmanagedResourceDirectories in Test += baseDirectory(_ / "target/web/public/test").value

resolvers += Resolver.jcenterRepo
resolvers += "scalaz-bintray" at "https://dl.bintray.com/scalaz/releases"

// Production front-end build
lazy val cleanFrontEndBuild = taskKey[Unit]("Remove the old front-end build")

cleanFrontEndBuild := {
  val d = file("public/bundle")
  if (d.exists()) {
    d.listFiles.foreach(f => {
      if (f.isFile) f.delete
    })
  }
}

lazy val frontEndBuild = taskKey[Unit]("Execute the npm build command to build the front-end")

frontEndBuild := {
  println(Process("npm install", file("front")).!!)
  println(Process("npm run build", file("front")).!!)
}

frontEndBuild := (frontEndBuild dependsOn cleanFrontEndBuild).value

dist := (dist dependsOn frontEndBuild).value

lazy val ConstantsPath = "app/lib/Constants.scala"


lazy val setVersionInSources = taskKey[Unit]("Sets build version into sources")

setVersionInSources := {
  import java.io.PrintWriter
  import scala.io.Source
  
  val version = sys.env.getOrElse("VERSION", throw new IllegalArgumentException("Missing VERSION env property"))
  println(s"Setting app version to $version")
  
  val src = Source.fromFile(ConstantsPath).mkString
  val updated = src.replaceAll(
    """final val versionStr: String = "\d+.\d+.\d+"""",
    s"""final val versionStr: String = "$version""""
  )

  val writer = new PrintWriter(new File(ConstantsPath))
  writer.write(updated)
  writer.close()
}

lazy val setSentryDsnInSources = taskKey[Unit]("Sets Sentry DSN into sources")

setSentryDsnInSources := {
  import java.io.PrintWriter

  import scala.io.Source

  sys.env.get("SENTRY_DSN").foreach { dsn =>
    println(s"Setting Sentry DSN")

    val src = Source.fromFile(ConstantsPath).mkString
    val updated = src.replace(
      """SentryDsn: Option[String] = None""",
      s"""SentryDsn: Option[String] = Some("$dsn")"""
    )

    val writer = new PrintWriter(new File(ConstantsPath))
    writer.write(updated)
    writer.close()
  }
}

sources in (Compile, doc) := Seq.empty
publishArtifact in (Compile, packageDoc) := false

PlayKeys.playDefaultPort := 3370
PlayKeys.devSettings := Seq("play.server.http.port" -> "3370")
