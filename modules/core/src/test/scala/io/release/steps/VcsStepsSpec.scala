package io.release.steps

import cats.effect.IO
import io.release.{ReleaseContext, TestAssertions, TestSupport}
import munit.CatsEffectSuite

import java.io.File

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
}
