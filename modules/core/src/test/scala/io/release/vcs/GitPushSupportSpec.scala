package io.release.vcs

import cats.effect.IO
import io.release.TestAssertions.assertFailure
import io.release.TestSupport
import munit.CatsEffectSuite

import java.io.File
import scala.concurrent.duration.FiniteDuration

class GitPushSupportSpec extends CatsEffectSuite {
  private val fixturePrefix = "git-push-support-spec"

  test("pushTag - reject empty or blank tag names before invoking git") {
    TestSupport.tempDirResource(fixturePrefix).use { dir =>
      val vcs = stubVcs(dir)

      assertFailure[IllegalStateException, Unit](GitPushSupport.pushTag(vcs, "origin", ""))(err =>
        assertEquals(err.getMessage, "Tag name cannot be empty when pushing to the remote.")
      ) *>
        assertFailure[IllegalStateException, Unit](GitPushSupport.pushTag(vcs, "origin", "   "))(
          err =>
            assertEquals(
              err.getMessage,
              "Tag name cannot be empty when pushing to the remote."
            )
        )
    }
  }

  private def stubVcs(dir: File): Vcs =
    new Vcs {
      override def commandName: String = "git"

      override def baseDir: File = dir

      override def currentHash: IO[String] = IO.pure("deadbeef")

      override def currentBranch: IO[String] = IO.pure("main")

      override def trackingRemote: IO[String] = IO.pure("origin")

      override def upstreamTrackingHash: IO[Option[String]] = IO.pure(Some("origin/main"))

      override def hasUpstream: IO[Boolean] = IO.pure(true)

      override def isBehindRemote: IO[Boolean] = IO.pure(false)

      override def existsTag(name: String): IO[Boolean] = IO.pure(false)

      override def modifiedFiles: IO[Seq[String]] = IO.pure(Nil)

      override def stagedFiles: IO[Seq[String]] = IO.pure(Nil)

      override def untrackedFiles: IO[Seq[String]] = IO.pure(Nil)

      override def status: IO[String] = IO.pure("")

      override def checkRemote(remote: String): IO[Int] = IO.pure(0)

      override def checkRemoteWithTimeout(
          remote: String,
          timeout: FiniteDuration
      ): IO[Option[Int]] =
        checkRemote(remote).map(Some(_))

      override def add(files: String*): IO[Unit] = IO.unit

      override def commit(message: String, sign: Boolean, signOff: Boolean): IO[Unit] =
        IO.unit

      override def tag(name: String, comment: String, sign: Boolean, force: Boolean): IO[Unit] =
        IO.unit

      override def pushChanges: IO[Unit] = IO.unit
    }
}
