package lib.server

import java.time.{Instant, ZoneId, ZonedDateTime}

import cats.data.NonEmptyList
import lib.client.clientapi.FileTreeNode.{Directory, RegularFile}
import lib.client.clientapi.Version
import lib.db.DbFile
import lib.server.serverapi.{RemoteFile, RemoteFileVersion}
import org.scalatest.FunSuite
import utils.Sha256

class CloudFilesRegistryTest extends FunSuite {
  private val fileVersion: RemoteFileVersion = {
    val time = ZonedDateTime.ofInstant(Instant.ofEpochMilli(42), ZoneId.systemDefault())
    RemoteFileVersion(0, 42, Sha256("0" * 64), time, time)
  }

  private def createDbFile(path: String): DbFile = {
    val remoteFile = RemoteFile(
      0,
      "testDevice",
      path,
      List(fileVersion)
    )

    DbFile(remoteFile.originalName, fileVersion.mtime, fileVersion.size, remoteFile)
  }

  test("backedUpList unix") {
    val paths = List(
      "/first/file.dat",
      "/first/second/file.dat",
      "/first/second/file2.dat",
      "/first/second/third/file2.dat",
      "/first/second/third/someDir/file5.dat",
      "/first/second/file3.dat",
      "/first/second_/file3.dat",
      "/anotherFirst/file4.dat"
    ).sorted

    val files: List[DbFile] = paths.map(createDbFile)

    assertResult(List(Directory("/", "/", None)))(CloudFilesRegistry.backedUpList(None, files, systemSlash = '/'))

    assertResult(
      List(
        Directory("/first", "first", None),
        Directory("/anotherFirst", "anotherFirst", None),
      ).sortBy(_.name))(CloudFilesRegistry.backedUpList(Some("/"), files, systemSlash = '/'))

    assertResult(
      List(
        Directory("/first/second", "second", None),
        Directory("/first/second_", "second_", None),
        RegularFile("/first/file.dat", "file.dat", Some(NonEmptyList.of(Version("/first/file.dat", fileVersion)))),
      ).sortBy(_.name))(CloudFilesRegistry.backedUpList(Some("/first"), files, systemSlash = '/'))

    assertResult(
      List(
        Directory("/first/second/third", "third", None),
        RegularFile("/first/second/file.dat", "file.dat", Some(NonEmptyList.of(Version("/first/second/file.dat", fileVersion)))),
        RegularFile("/first/second/file2.dat", "file2.dat", Some(NonEmptyList.of(Version("/first/second/file2.dat", fileVersion)))),
        RegularFile("/first/second/file3.dat", "file3.dat", Some(NonEmptyList.of(Version("/first/second/file3.dat", fileVersion)))),
      ).sortBy(_.name))(CloudFilesRegistry.backedUpList(Some("/first/second"), files, systemSlash = '/'))
  }

  test("backedUpList windows") {
    val paths = List(
      "c:\\dir\\file.dat",
      "c:\\file.dat",
      "c:\\dir\\dir2\\file.dat",
      "c:\\dir2\\dir3\\dir2file.dat",
      "c:\\anotherDir\\dir2file.dat",
      "d:\\dir\\dir2\\file.dat",
    ).sorted

    val files: List[DbFile] = paths.map(createDbFile)

    assertResult {
      List(
        Directory("c:", "c:", None),
        Directory("d:", "d:", None),
      )
    }(CloudFilesRegistry.backedUpList(None, files, systemSlash = '\\'))

    assertResult {
      List(
        Directory("c:\\dir", "dir", None),
        Directory("c:\\dir2", "dir2", None),
        Directory("c:\\anotherDir", "anotherDir", None),
        RegularFile("c:\\file.dat", "file.dat", Some(NonEmptyList.of(Version("c:\\file.dat", fileVersion))))
      ).sortBy(_.name)
    }(CloudFilesRegistry.backedUpList(Some("c:"), files, systemSlash = '\\'))

    assertResult {
      List(
        Directory("c:\\dir\\dir2", "dir2", None),
        RegularFile("c:\\dir\\file.dat", "file.dat", Some(NonEmptyList.of(Version("c:\\dir\\file.dat", fileVersion))))
      ).sortBy(_.name)
    }(CloudFilesRegistry.backedUpList(Some("c:\\dir\\"), files, systemSlash = '\\'))

    assertResult {
      List(
        Directory(s"c:\\dir2\\dir3", "dir3", None),
      ).sortBy(_.name)
    }(CloudFilesRegistry.backedUpList(Some("c:\\dir2"), files, systemSlash = '\\'))
  }
}
