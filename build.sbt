import com.typesafe.sbt.packager.MappingsHelper._

import scala.sys.process.Process

mappings in Universal ++= directory(baseDirectory.value / "public")

name := "rbackup-client"

version := "0.1"

scalaVersion := "2.12.6"

lazy val `rbackup-client` = (project in file(".")).enablePlugins(PlayScala)

libraryDependencies ++= Seq(guice, filters, jdbc, cacheApi, ws, specs2 % Test)

libraryDependencies ++= Seq(
  "io.monix" % "monix_2.12" % "3.0.0-RC1",
  "net.codingwell" %% "scala-guice" % "4.1.1",
  "com.dripower" %% "play-circe" % "2609.1",
  "com.typesafe.scala-logging" % "scala-logging_2.12" % "3.9.0"
)

// Play framework hooks for development
PlayKeys.playRunHooks += WebpackServer(file("./front"))

unmanagedResourceDirectories in Test += baseDirectory(_ / "target/web/public/test").value

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
