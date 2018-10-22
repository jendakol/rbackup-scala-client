import java.io.{File, FileOutputStream}

import com.typesafe.sbt.packager.MappingsHelper._
import sbtassembly.MergeStrategy

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

scalaVersion := "2.12.7"

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

lazy val makeProdJar = taskKey[Unit]("Creates a single fat JAR for production use")

makeProdJar := (assembly dependsOn frontEndBuild).value

mainClass in assembly := Some("play.core.server.ProdServerStart")
fullClasspath in assembly += Attributed.blank(PlayKeys.playPackageAssets.value)

val concatWithNewLine: MergeStrategy = new MergeStrategy {
  val name = "concatWithNewLine"

  def apply(tempDir: File, path: String, files: Seq[File]): Either[String, Seq[(File, String)]] = {
    val file = File.createTempFile("sbtMergeTarget", ".tmp", tempDir)
    val out = new FileOutputStream(file)
    try {
      files.reverse.foreach { f =>
        IO.transfer(f, out)
        out.write(s"\n\n// $f\n\n".getBytes)
      }
      Right(Seq(file -> path))
    } finally {
      out.close()
    }
  }
}

assemblyMergeStrategy in assembly := {
  case manifest if manifest.contains("MANIFEST.MF") =>
    // We don't need manifest files since sbt-assembly will create
    // one with the given settings
    MergeStrategy.discard
  case reference if reference.contains("reference.conf") =>
    // Keep the content for all reference-overrides.conf files
    concatWithNewLine
  case referenceOverrides if referenceOverrides.contains("reference-overrides.conf") =>
    // Keep the content for all reference-overrides.conf files
    MergeStrategy.concat
  case x =>
    // For all the other files, use the default sbt-assembly merge strategy
    val oldStrategy = (assemblyMergeStrategy in assembly).value
    oldStrategy(x)
}
