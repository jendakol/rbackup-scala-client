package lib

import io.circe.parser._
import lib.client.clientapi.BackupSetNode
import org.scalatest.FunSuite

import scala.io.Source

class BackupSetNodeTest extends FunSuite {
  test("parse") {
    val jsonStr = Source.fromURL(getClass.getClassLoader.getResource("backupSetFilesJson.json")).mkString

    val Right(files) = decode[Seq[BackupSetNode]](jsonStr)
    assertResult(1)(files.size)
    assertResult(Some("/"))(files.headOption.map(_.value))
  }

  test("flatten") {
    val jsonStr = Source.fromURL(getClass.getClassLoader.getResource("backupSetFilesJson.json")).mkString
    val Right(files) = decode[Seq[BackupSetNode]](jsonStr)

    def unroll(s: Seq[BackupSetNode]): Seq[BackupSetNode] = {
      s.flatMap(node => node +: unroll(node.children))
    }

    assertResult(408)(unroll(files).size)
    assertResult(408 - 72)(unroll(files).filterNot(_.loading).size)
    assertResult(unroll(files).filterNot(_.loading))(files.flatMap(_.flatten))
  }

  test("flattenNormalize") {
    val jsonStr = Source.fromURL(getClass.getClassLoader.getResource("backupSetFilesJson2.json")).mkString
    val Right(Seq(file)) = decode[Seq[BackupSetNode]](jsonStr)

    assertResult(10)(file.flatten.count(_.selected))

    assertResult {
      Set(
        "/bin",
        "/aaTestDir",
        "/aaTestDir/aaTestFile",
        "/aaTestDir/aaTestDirInner",
        "/aaTestDir/A77_32123.jpg",
        "/aaTestDir/apple-notifications.zip",
        "/aaTestDir/CrashPlan_4.8.0_Linux.tgz",
        "/aaTestDir/best-effort-classes-export-labels-nonpe-100k.csv",
        "/aaTestDir/AVG-email-templates-sazba.zip",
        "/aaTestDir/A77_32122.jpg"
      )
    }(file.flatten.filter(_.selected).map(_.value).toSet)

    assertResult(
      Set(
        "/bin",
        "/aaTestDir",
      ))(file.flattenNormalize.map(_.value).toSet)

  }
}
