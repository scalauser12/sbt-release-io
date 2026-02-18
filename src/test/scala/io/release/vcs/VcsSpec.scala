package io.release.vcs

import cats.effect.unsafe.implicits.global
import org.specs2.mutable.Specification

import java.io.File
import java.nio.file.Files
import scala.sys.process.Process

class VcsSpec extends Specification {

  "Vcs.detect" should {
    "fail when no VCS is present" in withTempDir { dir =>
      Vcs.detect(dir).unsafeRunSync() must throwA[RuntimeException]
    }

    "detect git and report a clean repo after initial commit" in withTempRepo { dir =>
      val vcs = Vcs.detect(dir).unsafeRunSync()

      vcs.isClean.unsafeRunSync() must beTrue
      vcs.hasModifiedFiles.unsafeRunSync() must beFalse
      vcs.hasUntrackedFiles.unsafeRunSync() must beFalse
      vcs.currentHash.unsafeRunSync() must not(beEmpty)
      vcs.currentBranch.unsafeRunSync() must not(beEmpty)
    }
  }

  "Vcs.hasChanges" should {
    "ignore untracked files but include staged tracked changes" in withTempRepo { dir =>
      val vcs = Vcs.detect(dir).unsafeRunSync()

      writeFile(dir, "untracked.txt", "hello")
      vcs.hasUntrackedFiles.unsafeRunSync() must beTrue
      vcs.hasChanges.unsafeRunSync() must beFalse

      vcs.add("untracked.txt").unsafeRunSync()
      vcs.hasChanges.unsafeRunSync() must beTrue
    }
  }

  "Vcs tag operations" should {
    "create and detect tags" in withTempRepo { dir =>
      val vcs = Vcs.detect(dir).unsafeRunSync()

      vcs.existsTag("v1.0.0").unsafeRunSync() must beFalse
      vcs.tag("v1.0.0", Some("first tag")).unsafeRunSync()
      vcs.existsTag("v1.0.0").unsafeRunSync() must beTrue
    }
  }

  private def withTempRepo[A](f: File => A): A = withTempDir { dir =>
    runOk(dir, Seq("git", "init"))
    runOk(dir, Seq("git", "config", "user.email", "test@example.com"))
    runOk(dir, Seq("git", "config", "user.name", "Test User"))
    writeFile(dir, "README.md", "init")
    runOk(dir, Seq("git", "add", "README.md"))
    runOk(dir, Seq("git", "commit", "-m", "init"))
    f(dir)
  }

  private def withTempDir[A](f: File => A): A = {
    val dir = Files.createTempDirectory("sbt-release-io-vcs-spec").toFile
    try f(dir)
    finally deleteRecursively(dir)
  }

  private def writeFile(base: File, relativePath: String, contents: String): Unit = {
    val file = new File(base, relativePath)
    val parent = file.getParentFile
    if (parent != null) parent.mkdirs()
    Files.write(file.toPath, contents.getBytes("UTF-8"))
  }

  private def runOk(baseDir: File, command: Seq[String]): Unit = {
    val exit = Process(command, baseDir).!
    if (exit != 0) {
      sys.error(s"Command failed (${command.mkString(" ")}), exit=$exit")
    }
  }

  private def deleteRecursively(file: File): Unit = {
    if (file.isDirectory) {
      val children = file.listFiles()
      if (children != null) children.foreach(deleteRecursively)
    }
    file.delete()
    ()
  }
}
