package lib

import cats.data
import cats.data.NonEmptyList
import cats.instances.string._
import lib.clientapi.FileTree
import lib.clientapi.FileTreeNode._
import lib.serverapi.RemoteFile
import org.scalatest.FunSuite
import views.html.helper.input

class FileTreeTest extends FunSuite {

  test("empty") {
    assertResult(FileTree.fromRemoteFiles(Seq.empty))(FileTree.fromNodes())
  }

  test("one level") {
    val input = Seq(
      RemoteFile(1, "d", "/file1", Vector.empty),
      RemoteFile(1, "d", "/file2", Vector.empty),
    )

    assertResult {
      Seq(
        FileTree("", Option {
          NonEmptyList.of(
            RegularFile("/file1", "file1", None),
            RegularFile("/file2", "file2", None),
          )
        })
      )
    }(FileTree.fromRemoteFiles(input))
  }

  test("combining FileTree") {
    val Seq(tree1: FileTree) = FileTree.fromNodes(
      Directory(
        "/dir1",
        "dir1",
        Some {
          NonEmptyList.of(
            RegularFile("/dir1/file3", "file3", None),
            Directory("/dir1/dir2", "dir2", Some {
              NonEmptyList.of(
                RegularFile("/dir1/dir2/file4", "file4", None)
              )
            })
          )
        }
      )
    )

    val Seq(tree2: FileTree) = FileTree.fromNodes(
      Directory(
        "/dir1",
        "dir1",
        Some {
          NonEmptyList.of(
            RegularFile("/dir1/file5", "file5", None),
            Directory("/dir1/dir2", "dir2", Some {
              NonEmptyList.of(
                RegularFile("/dir1/dir2/file6", "file6", None)
              )
            })
          )
        }
      )
    )

    val finalTrees = tree1 + tree2

    assertResult(1)(finalTrees.size)

    assertResult {
      FileTree.fromNodes(
        Directory(
          "/dir1",
          "dir1",
          Option {
            NonEmptyList
              .of(
                RegularFile("/dir1/file5", "file5", None),
                RegularFile("/dir1/file3", "file3", None),
                Directory("/dir1/dir2", "dir2", Some {
                  NonEmptyList
                    .of(
                      RegularFile("/dir1/dir2/file4", "file4", None),
                      RegularFile("/dir1/dir2/file6", "file6", None)
                    )
                    .sortBy(_.name)
                })
              )
              .sortBy(_.name)
          }
        )
      )
    }(finalTrees.toList)
  }

  test("advanced") {
    val input = Seq(
      RemoteFile(1, "dev", "c/file1", Vector.empty),
      RemoteFile(1, "dev", "c/file2", Vector.empty),
      RemoteFile(1, "dev", "c/dir1/file3", Vector.empty),
      RemoteFile(1, "dev", "c/dir1/file5", Vector.empty),
      RemoteFile(1, "dev", "c/dir1/dir2/file4", Vector.empty),
      RemoteFile(1, "dev", "c/dir1/dir2/file6", Vector.empty),
    )

    assertResult {
      FileTree.fromNodes(
        Directory(
          "c/dir1",
          "dir1",
          Option {
            NonEmptyList
              .of(
                RegularFile("c/dir1/file5", "file5", None),
                RegularFile("c/dir1/file3", "file3", None),
                Directory(
                  "c/dir1/dir2",
                  "dir2",
                  Some {
                    NonEmptyList
                      .of(
                        RegularFile("c/dir1/dir2/file4", "file4", None),
                        RegularFile("c/dir1/dir2/file6", "file6", None)
                      )
                      .sortBy(_.name)
                  }
                )
              )
              .sortBy(_.name)
          }
        ),
        RegularFile("c/file1", "file1", None),
        RegularFile("c/file2", "file2", None),
      )
    }(FileTree.fromRemoteFiles(input))
  }

  test("multiple file roots") {
    val input = Seq(
      RemoteFile(1, "dev", "c/file1", Vector.empty),
      RemoteFile(1, "dev", "c/file2", Vector.empty),
      RemoteFile(1, "dev", "c/dir1/file3", Vector.empty),
      RemoteFile(1, "dev", "c/dir1/file5", Vector.empty),
      RemoteFile(1, "dev", "c/dir1/dir2/file4", Vector.empty),
      RemoteFile(1, "dev", "c/dir1/dir2/file6", Vector.empty),
      RemoteFile(1, "dev", "d/dir3/dir4/file7", Vector.empty),
      RemoteFile(1, "dev", "d/dir5/dir6/file8", Vector.empty),
    )

    val fileTrees = FileTree.fromRemoteFiles(input)

    assertResult(2)(fileTrees.size)

    assertResult {
      FileTree.fromNodes(
        Directory(
          "c/dir1",
          "dir1",
          Option {
            NonEmptyList
              .of(
                RegularFile("c/dir1/file5", "file5", None),
                RegularFile("c/dir1/file3", "file3", None),
                Directory(
                  "c/dir1/dir2",
                  "dir2",
                  Some {
                    NonEmptyList
                      .of(
                        RegularFile("c/dir1/dir2/file4", "file4", None),
                        RegularFile("c/dir1/dir2/file6", "file6", None)
                      )
                      .sortBy(_.name)
                  }
                )
              )
              .sortBy(_.name)
          }
        ),
        RegularFile("c/file1", "file1", None),
        RegularFile("c/file2", "file2", None),
        Directory(
          "d/dir3",
          "dir3",
          Option {
            NonEmptyList
              .of(
                Directory("d/dir3/dir4", "dir4", Some {
                  NonEmptyList
                    .of(
                      RegularFile("d/dir3/dir4/file7", "file7", None),
                    )
                    .sortBy(_.name)
                })
              )
              .sortBy(_.name)
          }
        ),
        Directory(
          "d/dir5",
          "dir5",
          Option {
            NonEmptyList
              .of(
                Directory("d/dir5/dir6", "dir6", Some {
                  NonEmptyList
                    .of(
                      RegularFile("d/dir5/dir6/file8", "file8", None),
                    )
                    .sortBy(_.name)
                })
              )
              .sortBy(_.name)
          }
        ),
      )
    }(fileTrees)
  }

  test("toJson") {
    val input = Seq(
      RemoteFile(1, "dev", "c/file1", Vector.empty),
      RemoteFile(1, "dev", "c/file2", Vector.empty),
      RemoteFile(1, "dev", "c/dir1/file3", Vector.empty),
      RemoteFile(1, "dev", "c/dir1/file5", Vector.empty),
      RemoteFile(1, "dev", "c/dir1/dir2/file4", Vector.empty),
      RemoteFile(1, "dev", "c/dir1/dir2/file6", Vector.empty),
    )

    val Seq(fileTree) = FileTree.fromRemoteFiles(input)

    assertResult {
      """{"icon":"fas fa-folder icon-state-default","isLeaf":false,"opened":false,"value":"c/","text":"c","isFile":false,"isVersion":false,"isDir":true,"children":[{"icon":"fas fa-folder icon-state-default","isLeaf":false,"opened":false,"value":"c/dir1","text":"dir1","isFile":false,"isVersion":false,"isDir":true,"children":[{"icon":"fas fa-folder icon-state-default","isLeaf":false,"opened":false,"value":"c/dir1/dir2","text":"dir2","isFile":false,"isVersion":false,"isDir":true,"children":[{"icon":"fas fa-file icon-state-default","isLeaf":true,"opened":false,"value":"c/dir1/dir2/file4","text":"file4","isFile":true,"isVersion":false,"isDir":false},{"icon":"fas fa-file icon-state-default","isLeaf":true,"opened":false,"value":"c/dir1/dir2/file6","text":"file6","isFile":true,"isVersion":false,"isDir":false}]},{"icon":"fas fa-file icon-state-default","isLeaf":true,"opened":false,"value":"c/dir1/file3","text":"file3","isFile":true,"isVersion":false,"isDir":false},{"icon":"fas fa-file icon-state-default","isLeaf":true,"opened":false,"value":"c/dir1/file5","text":"file5","isFile":true,"isVersion":false,"isDir":false}]},{"icon":"fas fa-file icon-state-default","isLeaf":true,"opened":false,"value":"c/file1","text":"file1","isFile":true,"isVersion":false,"isDir":false},{"icon":"fas fa-file icon-state-default","isLeaf":true,"opened":false,"value":"c/file2","text":"file2","isFile":true,"isVersion":false,"isDir":false}]}""".stripMargin
    }(fileTree.toJson.noSpaces)
  }

  test("toJson multiple roots") {
    val input = Seq(
      RemoteFile(1, "dev", "c/file1", Vector.empty),
      RemoteFile(1, "dev", "c/file2", Vector.empty),
      RemoteFile(1, "dev", "c/dir1/file3", Vector.empty),
      RemoteFile(1, "dev", "c/dir1/file5", Vector.empty),
      RemoteFile(1, "dev", "c/dir1/dir2/file4", Vector.empty),
      RemoteFile(1, "dev", "c/dir1/dir2/file6", Vector.empty),
      RemoteFile(1, "dev", "d/dir3/dir4/file7", Vector.empty),
      RemoteFile(1, "dev", "d/dir5/dir6/file8", Vector.empty),
    )

    val fileTrees = FileTree.fromRemoteFiles(input)

    assertResult(2)(fileTrees.size)

    assertResult {
      """[{"icon":"fas fa-folder icon-state-default","isLeaf":false,"opened":false,"value":"d/","text":"d","isFile":false,"isVersion":false,"isDir":true,"children":[{"icon":"fas fa-folder icon-state-default","isLeaf":false,"opened":false,"value":"d/dir3","text":"dir3","isFile":false,"isVersion":false,"isDir":true,"children":[{"icon":"fas fa-folder icon-state-default","isLeaf":false,"opened":false,"value":"d/dir3/dir4","text":"dir4","isFile":false,"isVersion":false,"isDir":true,"children":[{"icon":"fas fa-file icon-state-default","isLeaf":true,"opened":false,"value":"d/dir3/dir4/file7","text":"file7","isFile":true,"isVersion":false,"isDir":false}]}]},{"icon":"fas fa-folder icon-state-default","isLeaf":false,"opened":false,"value":"d/dir5","text":"dir5","isFile":false,"isVersion":false,"isDir":true,"children":[{"icon":"fas fa-folder icon-state-default","isLeaf":false,"opened":false,"value":"d/dir5/dir6","text":"dir6","isFile":false,"isVersion":false,"isDir":true,"children":[{"icon":"fas fa-file icon-state-default","isLeaf":true,"opened":false,"value":"d/dir5/dir6/file8","text":"file8","isFile":true,"isVersion":false,"isDir":false}]}]}]},{"icon":"fas fa-folder icon-state-default","isLeaf":false,"opened":false,"value":"c/","text":"c","isFile":false,"isVersion":false,"isDir":true,"children":[{"icon":"fas fa-folder icon-state-default","isLeaf":false,"opened":false,"value":"c/dir1","text":"dir1","isFile":false,"isVersion":false,"isDir":true,"children":[{"icon":"fas fa-folder icon-state-default","isLeaf":false,"opened":false,"value":"c/dir1/dir2","text":"dir2","isFile":false,"isVersion":false,"isDir":true,"children":[{"icon":"fas fa-file icon-state-default","isLeaf":true,"opened":false,"value":"c/dir1/dir2/file4","text":"file4","isFile":true,"isVersion":false,"isDir":false},{"icon":"fas fa-file icon-state-default","isLeaf":true,"opened":false,"value":"c/dir1/dir2/file6","text":"file6","isFile":true,"isVersion":false,"isDir":false}]},{"icon":"fas fa-file icon-state-default","isLeaf":true,"opened":false,"value":"c/dir1/file3","text":"file3","isFile":true,"isVersion":false,"isDir":false},{"icon":"fas fa-file icon-state-default","isLeaf":true,"opened":false,"value":"c/dir1/file5","text":"file5","isFile":true,"isVersion":false,"isDir":false}]},{"icon":"fas fa-file icon-state-default","isLeaf":true,"opened":false,"value":"c/file1","text":"file1","isFile":true,"isVersion":false,"isDir":false},{"icon":"fas fa-file icon-state-default","isLeaf":true,"opened":false,"value":"c/file2","text":"file2","isFile":true,"isVersion":false,"isDir":false}]}]""".stripMargin
    }(fileTrees.toJson.noSpaces)
  }

  test("flatten") {
    val input = Seq(
      RemoteFile(1, "dev", "c/file1", Vector.empty),
      RemoteFile(1, "dev", "c/file2", Vector.empty),
      RemoteFile(1, "dev", "c/dir1/file3", Vector.empty),
      RemoteFile(1, "dev", "c/dir1/file5", Vector.empty),
      RemoteFile(1, "dev", "c/dir1/dir2/file4", Vector.empty),
      RemoteFile(1, "dev", "c/dir1/dir2/file6", Vector.empty),
    )

    assertResult {
      Some(
        NonEmptyList
          .of(
            RegularFile("c/dir1/file5", "file5", None),
            RegularFile("c/dir1/file3", "file3", None),
            RegularFile("c/dir1/dir2/file4", "file4", None),
            RegularFile("c/dir1/dir2/file6", "file6", None),
            RegularFile("c/file1", "file1", None),
            RegularFile("c/file2", "file2", None)
          )
          .sortBy(_.path))
    }(FileTree.fromRemoteFiles(input).head.allFiles.map(_.sortBy(_.path)))
  }

  test("flatten with multiple file roots") {
    val input = Seq(
      RemoteFile(1, "dev", "c/file1", Vector.empty),
      RemoteFile(1, "dev", "c/file2", Vector.empty),
      RemoteFile(1, "dev", "c/dir1/file3", Vector.empty),
      RemoteFile(1, "dev", "c/dir1/file5", Vector.empty),
      RemoteFile(1, "dev", "c/dir1/dir2/file4", Vector.empty),
      RemoteFile(1, "dev", "c/dir1/dir2/file6", Vector.empty),
      RemoteFile(1, "dev", "d/dir3/dir4/file7", Vector.empty),
      RemoteFile(1, "dev", "d/dir5/dir6/file8", Vector.empty),
    )

    val fileTrees = FileTree.fromRemoteFiles(input)

    assertResult(2)(fileTrees.size)

    assertResult {
      NonEmptyList
        .of(
          RegularFile("c/dir1/file5", "file5", None),
          RegularFile("c/dir1/file3", "file3", None),
          RegularFile("c/dir1/dir2/file4", "file4", None),
          RegularFile("c/dir1/dir2/file6", "file6", None),
          RegularFile("c/file1", "file1", None),
          RegularFile("c/file2", "file2", None),
          RegularFile("d/dir3/dir4/file7", "file7", None),
          RegularFile("d/dir5/dir6/file8", "file8", None)
        )
        .sortBy(_.path)
    }(fileTrees.flatMap(_.allFiles).reduce(_ ::: _).sortBy(_.path))
  }
}
