package io.release

import cats.effect.IO
import munit.CatsEffectSuite

import java.nio.charset.StandardCharsets
import java.nio.file.Files
import java.nio.file.LinkOption

class TestSupportSpec extends CatsEffectSuite {

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
