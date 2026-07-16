package io.release.runtime.workflow

import cats.effect.Deferred
import cats.effect.IO
import cats.effect.Ref
import io.release.vcs.Vcs
import munit.CatsEffectSuite

import java.io.File

class VersionCommitSupportSpec extends CatsEffectSuite {

  test("versionFileStatus - use only the exact configured path to decide whether to commit") {
    val expectedPath = "config/release-version.sbt"

    for {
      clean     <- VersionCommitSupport.versionFileStatus(expectedPath, statusVcs())
      modified  <- VersionCommitSupport.versionFileStatus(
                     expectedPath,
                     statusVcs(modified = Seq(expectedPath))
                   )
      staged    <- VersionCommitSupport.versionFileStatus(
                     expectedPath,
                     statusVcs(staged = Seq(expectedPath))
                   )
      untracked <- VersionCommitSupport.versionFileStatus(
                     expectedPath,
                     statusVcs(untracked = Seq(expectedPath))
                   )
      unrelated <- VersionCommitSupport.versionFileStatus(
                     expectedPath,
                     statusVcs(
                       modified = Seq("other-modified.sbt"),
                       staged = Seq("other-staged.sbt"),
                       untracked = Seq("other-untracked.sbt")
                     )
                   )
    } yield {
      assertEquals(
        clean,
        VersionCommitSupport.VersionFileStatus(
          modified = false,
          staged = false,
          untracked = false
        )
      )
      assert(!clean.commitNeeded(writeWouldChange = false))
      assert(clean.commitNeeded(writeWouldChange = true))

      assertEquals(
        modified,
        VersionCommitSupport.VersionFileStatus(
          modified = true,
          staged = false,
          untracked = false
        )
      )
      assert(modified.commitNeeded(writeWouldChange = false))

      assertEquals(
        staged,
        VersionCommitSupport.VersionFileStatus(
          modified = false,
          staged = true,
          untracked = false
        )
      )
      assert(staged.commitNeeded(writeWouldChange = false))

      assertEquals(
        untracked,
        VersionCommitSupport.VersionFileStatus(
          modified = false,
          staged = false,
          untracked = true
        )
      )
      assert(untracked.commitNeeded(writeWouldChange = false))

      assertEquals(
        unrelated,
        VersionCommitSupport.VersionFileStatus(
          modified = false,
          staged = false,
          untracked = false
        )
      )
      assert(!unrelated.commitNeeded(writeWouldChange = false))
    }
  }

  test("stageAndCommitIfChangedAtomic - retain HEAD and verify a no-op") {
    for {
      events <- Ref.of[IO, Vector[String]](Vector.empty)
      vcs     = recordingVcs(
                  events,
                  staged = Seq.empty,
                  hash = "existing-head"
                )
      result <- VersionCommitSupport.stageAndCommitIfChangedAtomic(
                  vcs,
                  Seq("version.sbt"),
                  "Set release version",
                  sign = false,
                  signOff = false,
                  postCommitVerify = events.update(_ :+ "verify")
                )
      seen   <- events.get
    } yield {
      assertEquals(
        result,
        VersionCommitSupport.ConditionalCommitResult("existing-head", committed = false)
      )
      assertEquals(seen, Vector("add:version.sbt", "staged", "hash", "verify"))
    }
  }

  test("stageAndCommitIfChangedAtomic - commit staged changes and return the new HEAD") {
    for {
      events <- Ref.of[IO, Vector[String]](Vector.empty)
      vcs     = recordingVcs(
                  events,
                  staged = Seq("version.sbt"),
                  hash = "new-head"
                )
      result <- VersionCommitSupport.stageAndCommitIfChangedAtomic(
                  vcs,
                  Seq("version.sbt"),
                  "Set release version",
                  sign = true,
                  signOff = true,
                  postCommitVerify = events.update(_ :+ "verify")
                )
      seen   <- events.get
    } yield {
      assertEquals(
        result,
        VersionCommitSupport.ConditionalCommitResult("new-head", committed = true)
      )
      assertEquals(
        seen,
        Vector(
          "add:version.sbt",
          "staged",
          "commit:Set release version:true:true",
          "hash",
          "verify"
        )
      )
    }
  }

  test("stageAndCommitIfChangedAtomic - finish commit and verification after cancellation") {
    for {
      addStarted       <- Deferred[IO, Unit]
      allowAddToFinish <- Deferred[IO, Unit]
      commitCalls      <- Ref.of[IO, Int](0)
      verified         <- Ref.of[IO, Boolean](false)
      vcs               = new StubVcs {
                            override def add(files: String*): IO[Unit] =
                              addStarted.complete(()).void *> allowAddToFinish.get

                            override def stagedFiles: IO[Seq[String]] =
                              IO.pure(Seq("version.sbt"))

                            override def commit(
                                message: String,
                                sign: Boolean,
                                signOff: Boolean
                            ): IO[Unit] = commitCalls.update(_ + 1)
                          }
      fiber            <- VersionCommitSupport
                            .stageAndCommitIfChangedAtomic(
                              vcs,
                              Seq("version.sbt"),
                              "Set release version",
                              sign = false,
                              signOff = false,
                              postCommitVerify = verified.set(true)
                            )
                            .start
      _                <- addStarted.get
      cancelFiber      <- fiber.cancel.start
      _                <- allowAddToFinish.complete(()).void
      _                <- cancelFiber.join
      _                <- fiber.join
      calls            <- commitCalls.get
      didVerify        <- verified.get
    } yield {
      assertEquals(calls, 1)
      assert(didVerify)
    }
  }

  private def recordingVcs(
      events: Ref[IO, Vector[String]],
      staged: Seq[String],
      hash: String
  ): Vcs =
    new StubVcs {
      override def add(files: String*): IO[Unit] =
        events.update(_ :+ s"add:${files.mkString(",")}")

      override def stagedFiles: IO[Seq[String]] =
        events.update(_ :+ "staged").as(staged)

      override def commit(message: String, sign: Boolean, signOff: Boolean): IO[Unit] =
        events.update(_ :+ s"commit:$message:$sign:$signOff")

      override def currentHash: IO[String] =
        events.update(_ :+ "hash").as(hash)
    }

  private def statusVcs(
      modified: Seq[String] = Seq.empty,
      staged: Seq[String] = Seq.empty,
      untracked: Seq[String] = Seq.empty
  ): Vcs =
    new StubVcs {
      override def modifiedFiles: IO[Seq[String]]  = IO.pure(modified)
      override def stagedFiles: IO[Seq[String]]    = IO.pure(staged)
      override def untrackedFiles: IO[Seq[String]] = IO.pure(untracked)
    }

  private abstract class StubVcs extends Vcs {
    override val commandName: String = "test"
    override val baseDir: File       = new File(".")

    override def currentHash: IO[String]                                            = IO.pure("head")
    override def currentBranch: IO[String]                                          = IO.pure("main")
    override def trackingRemote: IO[String]                                         = IO.pure("origin")
    override def upstreamTrackingHash: IO[Option[String]]                           = IO.pure(None)
    override def hasUpstream: IO[Boolean]                                           = IO.pure(false)
    override def isBehindRemote: IO[Boolean]                                        = IO.pure(false)
    override def existsTag(name: String): IO[Boolean]                               = IO.pure(false)
    override def modifiedFiles: IO[Seq[String]]                                     = IO.pure(Seq.empty)
    override def stagedFiles: IO[Seq[String]]                                       = IO.pure(Seq.empty)
    override def untrackedFiles: IO[Seq[String]]                                    = IO.pure(Seq.empty)
    override def status: IO[String]                                                 = IO.pure("")
    override def checkRemote(remote: String): IO[Int]                               = IO.pure(0)
    override def add(files: String*): IO[Unit]                                      = IO.unit
    override def commit(message: String, sign: Boolean, signOff: Boolean): IO[Unit] = IO.unit
    override def tag(
        name: String,
        comment: String,
        sign: Boolean,
        force: Boolean
    ): IO[Unit]                                                                     = IO.unit
    override def pushChanges: IO[Unit]                                              = IO.unit
  }
}
