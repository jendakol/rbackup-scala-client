import com.typesafe.sbt.packager.MappingsHelper._

import scala.sys.process.Process

lazy val Versions = new {
  val monix = "3.0.0-RC1"
  val http4s = "0.18.15"
  val scalikeJdbc = "3.3.1"
  val metricsVersion = "2.4.4"
}

mappings in Universal ++= directory(baseDirectory.value / "public")

name := "rbackup-client"

version := "0.1"

scalaVersion := "2.12.6"

lazy val `rbackup-client` = (project in file(".")).enablePlugins(PlayScala)

libraryDependencies ++= Seq(guice, filters, jdbc, cacheApi, ws, specs2 % Test)

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
  "com.dripower" %% "play-circe" % "2609.1",
  "io.circe" %% "circe-generic-extras" % "0.9.3",
  "com.avast.metrics" %% "metrics-scala" % Versions.metricsVersion,
  "com.avast.metrics" % "metrics-statsd" % Versions.metricsVersion,
  "com.typesafe.scala-logging" %% "scala-logging" % "3.9.0",
  "org.scalatest" %% "scalatest" % "3.0.5" % "test"
)

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
