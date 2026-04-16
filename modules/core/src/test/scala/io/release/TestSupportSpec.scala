package io.release

import cats.effect.IO
import munit.CatsEffectSuite

import java.nio.charset.StandardCharsets
import java.nio.file.Files
import java.nio.file.LinkOption

class TestSupportSpec extends CatsEffectSuite {

  test("TestRepoFiles.resolve - find tracked files from a nested working directory") {
    IO.blocking {
      val repoRoot   = TestRepoFiles.resolve("build.sbt").getParent
      val nestedDir  = repoRoot.resolve("modules").resolve("core").resolve("src").resolve("test")
      val originalWd = sys.props("user.dir")

      try {
        sys.props("user.dir") = nestedDir.toString
        val resolved        = TestRepoFiles.resolve("build.sbt")
        val buildVersions   = TestRepoFiles.readString("project/src/main/scala/BuildVersions.scala")
        val buildProperties = TestRepoFiles.readString("project/build.properties").trim
        val sbt2Version     = TestRepoFiles.readString("project/sbt2.version").trim

        assertEquals(resolved.normalize(), repoRoot.resolve("build.sbt").normalize())
        assert(buildVersions.contains("project/build.properties"))
        assert(buildVersions.contains("project/sbt2.version"))
        assertEquals(buildProperties, "sbt.version=1.12.3")
        assertEquals(sbt2Version, "2.0.0-RC9")
      } finally sys.props("user.dir") = originalWd
    }
  }

  test("dummyAppConfiguration - expose the current runtime Scala version") {
    TestSupport.tempDirResource("test-support-app-config").use { dir =>
      IO.blocking {
        val scalaVersion =
          TestSupport.dummyAppConfiguration(dir).provider().scalaProvider().version()

        assertEquals(scalaVersion, TestSupport.CurrentScalaVersion)
      }
    }
  }

  test("deleteRecursively - delete a symlink without deleting its target contents") {
    TestSupport.tempDirResource("test-support-delete").use { dir =>
      IO.blocking {
        val target = Files.createTempDirectory("test-support-delete-target")

        try {
          val targetFile = target.resolve("keep.txt")
          val link       = dir.toPath.resolve("linked-target")

          Files.write(targetFile, "keep".getBytes(StandardCharsets.UTF_8))
          Files.createSymbolicLink(link, target)

          TestSupport.deleteRecursively(dir)

          assert(Files.exists(targetFile))
          assert(!Files.exists(link, LinkOption.NOFOLLOW_LINKS))
        } finally TestSupport.deleteRecursively(target.toFile)
      }
    }
  }
}
