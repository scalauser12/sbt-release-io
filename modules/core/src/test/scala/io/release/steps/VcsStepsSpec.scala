package io.release.steps

import cats.effect.IO
import cats.effect.Resource
import io.release.ReleaseContext
import io.release.TestAssertions
import io.release.TestSupport
import io.release.internal.CoreExecutionState
import io.release.internal.CoreReleasePlan
import io.release.internal.ExecutionFlags
import munit.CatsEffectSuite

import java.io.ByteArrayInputStream
import java.io.File
import java.io.InputStream
import java.nio.charset.StandardCharsets
import java.util.concurrent.Semaphore

class VcsStepsSpec extends CatsEffectSuite {
  private val fixturePrefix = "vcs-steps-spec"

  test("initializeVcs - detect Git from the loaded project base") {
    TestSupport.gitRepoWithLoadedStateResource(fixturePrefix).use { case (_, state) =>
      VcsSteps.initializeVcs.execute(ReleaseContext(state = state)).map { result =>
        assertEquals(result.vcs.map(_.commandName), Some("git"))
      }
    }
  }

  test("checkCleanWorkingDir.validate - succeed for a clean loaded repo") {
    TestSupport.gitRepoWithLoadedStateResource(fixturePrefix).use { case (_, state) =>
      VcsSteps.checkCleanWorkingDir.validate(ReleaseContext(state = state))
    }
  }

  test("checkCleanWorkingDir.validate - fail for a dirty tracked file in a loaded repo") {
    TestSupport.gitRepoWithLoadedStateResource(fixturePrefix).use { case (repo, state) =>
      IO.blocking(sbt.IO.write(new File(repo, "file.txt"), "modified")) *>
        TestAssertions.assertFailure[IllegalStateException, Unit](
          VcsSteps.checkCleanWorkingDir.validate(ReleaseContext(state = state))
        ) { err =>
          assert(err.getMessage.contains("unstaged modified files"))
          assert(err.getMessage.contains("file.txt"))
        }
    }
  }

  test("pushChanges.validate - pass with a broken tracking remote when upstream is configured") {
    TestSupport.brokenRemoteContextResource(fixturePrefix).use { ctx =>
      VcsSteps.pushChanges.validate(ctx)
    }
  }

  test("pushChanges.execute - fail during remote preflight in non-interactive mode") {
    TestSupport.brokenRemoteContextResource(fixturePrefix).use { ctx =>
      TestAssertions.assertFailure[IllegalStateException, ReleaseContext](
        VcsSteps.pushChanges.execute(ctx)
      )(err => assert(err.getMessage.contains("Aborting the release due to remote check failure.")))
    }
  }

  test("tagRelease.execute - abort in non-interactive mode when the tag already exists") {
    TestSupport.gitRepoWithCommitResource(fixturePrefix).use { case (repo, vcs) =>
      val state = TestSupport.gitRootState(
        repo,
        Seq(
          io.release.ReleaseIO.releaseIOVcsSign    := false,
          io.release.ReleaseIO.releaseIOTagName    := "v1.0.0",
          io.release.ReleaseIO.releaseIOTagComment := "Releasing 1.0.0"
        )
      )

      IO.blocking(TestSupport.runGit(repo, "tag", "v1.0.0")) *>
        TestAssertions.assertIllegalStateMessage(
          VcsSteps.tagRelease
            .execute(ReleaseContext(state = state, vcs = Some(vcs), interactive = false)),
          "Tag [v1.0.0] already exists. Aborting release in non-interactive mode."
        )
    }
  }

  test("tagRelease.execute - create the tag and keep the resulting context usable") {
    TestSupport.gitRepoWithCommitResource(fixturePrefix).use { case (repo, vcs) =>
      val versionFile = new File(repo, "version.sbt")
      val state       = TestSupport.gitRootState(
        repo,
        Seq(
          sbt.Keys.packageOptions                           := Seq.empty,
          io.release.ReleaseIO.releaseIOVersionFile         := versionFile,
          io.release.ReleaseIO.releaseIOReadVersion         := VersionSteps.defaultReadVersion,
          io.release.ReleaseIO.releaseIOVersionFileContents := VersionSteps.defaultWriteVersion(
            useGlobalVersion = true
          ),
          io.release.ReleaseIO.releaseIOUseGlobalVersion    := true,
          io.release.ReleaseIO.releaseIOVcsSign             := false,
          io.release.ReleaseIO.releaseIOTagName             := "v1.0.1",
          io.release.ReleaseIO.releaseIOTagComment          := "Releasing 1.0.1"
        )
      )

      for {
        result <- VcsSteps.tagRelease.execute(
                    ReleaseContext(state = state, vcs = Some(vcs), interactive = false)
                  )
        _      <- VcsSteps.checkCleanWorkingDir.validate(result)
        tags   <- IO.blocking(TestSupport.runGit(repo, "tag", "--list", "v1.0.1"))
      } yield {
        assertEquals(tags.trim, "v1.0.1")
        assertEquals(result.vcs.map(_.commandName), Some("git"))
      }
    }
  }

  test("tagRelease.execute - treat EOF as the default abort answer when the tag already exists") {
    TestSupport.gitRepoWithCommitResource(fixturePrefix).use { case (repo, vcs) =>
      val state = TestSupport.gitRootState(
        repo,
        Seq(
          io.release.ReleaseIO.releaseIOVcsSign    := false,
          io.release.ReleaseIO.releaseIOTagName    := "v1.0.0",
          io.release.ReleaseIO.releaseIOTagComment := "Releasing 1.0.0"
        )
      )

      IO.blocking(TestSupport.runGit(repo, "tag", "v1.0.0")) *>
        withInput("") {
          TestAssertions.assertIllegalStateMessage(
            VcsSteps.tagRelease.execute(
              ReleaseContext(state = state, vcs = Some(vcs), interactive = true)
            ),
            "Tag [v1.0.0] already exists. Aborting release!"
          )
        }
    }
  }

  test("preflightTag - fail deterministically in non-interactive mode when the tag exists") {
    TestSupport.gitRepoWithCommitResource(fixturePrefix).use { case (repo, vcs) =>
      val state = TestSupport.gitRootState(
        repo,
        Seq(
          io.release.ReleaseIO.releaseIOVcsSign    := false,
          io.release.ReleaseIO.releaseIOTagName    := "v1.0.0",
          io.release.ReleaseIO.releaseIOTagComment := "Releasing 1.0.0"
        )
      )

      IO.blocking(TestSupport.runGit(repo, "tag", "v1.0.0")) *>
        TestAssertions.assertFailure[IllegalStateException, VcsSteps.PreflightTagOutcome](
          VcsSteps.preflightTag(
            ReleaseContext(state = state, vcs = Some(vcs), interactive = false)
              .withVersions("1.0.0", "1.1.0-SNAPSHOT")
          )
        ) { err =>
          assert(err.getMessage.contains("Current settings would abort in non-interactive mode"))
          assert(err.getMessage.contains("releaseIO help"))
        }
    }
  }

  test("preflightTag - report keep behavior when the configured default answer is keep") {
    TestSupport.gitRepoWithCommitResource(fixturePrefix).use { case (repo, vcs) =>
      val state = TestSupport.gitRootState(
        repo,
        Seq(
          io.release.ReleaseIO.releaseIOVcsSign    := false,
          io.release.ReleaseIO.releaseIOTagName    := "v1.0.0",
          io.release.ReleaseIO.releaseIOTagComment := "Releasing 1.0.0"
        )
      )
      val ctx   = ReleaseContext(state = state, vcs = Some(vcs), interactive = false)
        .withVersions("1.0.0", "1.1.0-SNAPSHOT")
        .withExecutionState(
          CoreExecutionState(
            CoreReleasePlan(
              flags = ExecutionFlags(
                useDefaults = false,
                skipTests = false,
                skipPublish = false,
                interactive = false,
                crossBuild = false
              ),
              releaseVersionOverride = None,
              nextVersionOverride = None,
              tagDefault = Some("k")
            )
          )
        )

      IO.blocking(TestSupport.runGit(repo, "tag", "v1.0.0")) *>
        VcsSteps.preflightTag(ctx).map { outcome =>
          assertEquals(outcome.tagName, "v1.0.0")
          assertEquals(outcome.status, "exists; release will keep the existing tag")
        }
    }
  }

  test("preflightTag - report interactive prompt behavior when the tag exists") {
    TestSupport.gitRepoWithCommitResource(fixturePrefix).use { case (repo, vcs) =>
      val state = TestSupport.gitRootState(
        repo,
        Seq(
          io.release.ReleaseIO.releaseIOVcsSign    := false,
          io.release.ReleaseIO.releaseIOTagName    := "v1.0.0",
          io.release.ReleaseIO.releaseIOTagComment := "Releasing 1.0.0"
        )
      )

      IO.blocking(TestSupport.runGit(repo, "tag", "v1.0.0")) *>
        VcsSteps
          .preflightTag(
            ReleaseContext(state = state, vcs = Some(vcs), interactive = true)
              .withVersions("1.0.0", "1.1.0-SNAPSHOT")
          )
          .map { outcome =>
            assertEquals(outcome.tagName, "v1.0.0")
            assertEquals(
              outcome.status,
              "exists; interactive release will prompt for overwrite, keep, abort, or a new tag"
            )
          }
    }
  }

  test("preflightTag - use the configured command name in tag conflict guidance") {
    TestSupport.gitRepoWithCommitResource(fixturePrefix).use { case (repo, vcs) =>
      val state = TestSupport.gitRootState(
        repo,
        Seq(
          io.release.ReleaseIO.releaseIOVcsSign    := false,
          io.release.ReleaseIO.releaseIOTagName    := "v1.0.0",
          io.release.ReleaseIO.releaseIOTagComment := "Releasing 1.0.0"
        )
      )
      val ctx   = ReleaseContext(state = state, vcs = Some(vcs), interactive = false)
        .withVersions("1.0.0", "1.1.0-SNAPSHOT")
        .withExecutionState(
          CoreExecutionState(
            CoreReleasePlan(
              flags = ExecutionFlags(
                useDefaults = false,
                skipTests = false,
                skipPublish = false,
                interactive = false,
                crossBuild = false
              ),
              releaseVersionOverride = None,
              nextVersionOverride = None,
              tagDefault = None,
              commandName = "releaseCustom"
            )
          )
        )

      IO.blocking(TestSupport.runGit(repo, "tag", "v1.0.0")) *>
        TestAssertions.assertFailure[IllegalStateException, VcsSteps.PreflightTagOutcome](
          VcsSteps.preflightTag(ctx)
        ) { err =>
          assert(err.getMessage.contains("releaseCustom help"))
          assert(!err.getMessage.contains("releaseIO help"))
        }
    }
  }

  private val stdinLock = new Semaphore(1)

  private def withInput[A](input: String)(io: IO[A]): IO[A] = {
    val bytes = input.getBytes(StandardCharsets.UTF_8)

    Resource
      .make {
        IO.blocking {
          stdinLock.acquire()
          val original = System.in
          System.setIn(new ByteArrayInputStream(bytes))
          original
        }
      }(restoreInput)
      .use(_ => io)
  }

  private def restoreInput(original: InputStream): IO[Unit] =
    IO.blocking {
      System.setIn(original)
      stdinLock.release()
    }
}
