package io.release.steps

import cats.effect.{IO, Resource}
import io.release.{ReleaseContext, TestSupport}
import munit.CatsEffectSuite
import sbt.{Def, Project}

import java.io.File

class VcsStepsSpec extends CatsEffectSuite {

  test("initializeVcs - detect Git from the loaded project base") {
    gitRepoWithLoadedStateResource.use { case (_, state) =>
      VcsSteps.initializeVcs.execute(ReleaseContext(state = state)).map { result =>
        assertEquals(result.vcs.map(_.commandName), Some("git"))
      }
    }
  }

  test("checkCleanWorkingDir.validate - succeed for a clean loaded repo") {
    gitRepoWithLoadedStateResource.use { case (_, state) =>
      VcsSteps.checkCleanWorkingDir.validate(ReleaseContext(state = state)).map { result =>
        assertEquals(result, ())
      }
    }
  }

  test("checkCleanWorkingDir.validate - fail for a dirty tracked file in a loaded repo") {
    gitRepoWithLoadedStateResource.use { case (repo, state) =>
      IO.blocking(sbt.IO.write(new File(repo, "file.txt"), "modified")) *>
        VcsSteps.checkCleanWorkingDir
          .validate(ReleaseContext(state = state))
          .attempt
          .map {
            case Left(err: IllegalStateException) =>
              assert(err.getMessage.contains("unstaged modified files"))
              assert(err.getMessage.contains("file.txt"))
            case other                            =>
              fail(s"Expected IllegalStateException but got $other")
          }
    }
  }

  test("pushChanges.validate - pass with a broken tracking remote when upstream is configured") {
    releaseContextResource.use { ctx =>
      VcsSteps.pushChanges.validate(ctx).map { result =>
        assertEquals(result, ())
      }
    }
  }

  test("pushChanges.execute - fail during remote preflight in non-interactive mode") {
    releaseContextResource.use { ctx =>
      VcsSteps.pushChanges.execute(ctx).attempt.map {
        case Left(err: IllegalStateException) =>
          assert(err.getMessage.contains("Aborting the release due to remote check failure."))
        case other                            =>
          fail(s"Expected IllegalStateException but got $other")
      }
    }
  }

  test("tagRelease.execute - abort in non-interactive mode when the tag already exists") {
    gitRepoWithCommitResource.use { case (repo, vcs) =>
      val state = loadedState(
        repo,
        Seq(
          io.release.ReleaseIO.releaseIOVcsSign    := false,
          io.release.ReleaseIO.releaseIOTagName    := "v1.0.0",
          io.release.ReleaseIO.releaseIOTagComment := "Releasing 1.0.0"
        )
      )

      IO.blocking(TestSupport.runGit(repo, "tag", "v1.0.0")) *>
        VcsSteps.tagRelease
          .execute(ReleaseContext(state = state, vcs = Some(vcs), interactive = false))
          .attempt
          .map {
            case Left(err: IllegalStateException) =>
              assertEquals(
                err.getMessage,
                "Tag [v1.0.0] already exists. Aborting release in non-interactive mode."
              )
            case other                            =>
              fail(s"Expected IllegalStateException but got $other")
          }
    }
  }

  test("tagRelease.execute - create the tag and keep the resulting context usable") {
    gitRepoWithCommitResource.use { case (repo, vcs) =>
      val versionFile = new File(repo, "version.sbt")
      val state = loadedState(
        repo,
        Seq(
          sbt.Keys.packageOptions                       := Seq.empty,
          io.release.ReleaseIO.releaseIOVersionFile         := versionFile,
          io.release.ReleaseIO.releaseIOReadVersion         := VersionSteps.defaultReadVersion,
          io.release.ReleaseIO.releaseIOVersionFileContents := VersionSteps.defaultWriteVersion(
            useGlobalVersion = true
          ),
          io.release.ReleaseIO.releaseIOUseGlobalVersion    := true,
          io.release.ReleaseIO.releaseIOVcsSign    := false,
          io.release.ReleaseIO.releaseIOTagName    := "v1.0.1",
          io.release.ReleaseIO.releaseIOTagComment := "Releasing 1.0.1"
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

  private val tempDirResource: Resource[IO, File] =
    TestSupport.tempDirResource("vcs-steps-spec")

  private val releaseContextResource: Resource[IO, ReleaseContext] =
    tempDirResource.evalMap { repo =>
      TestSupport.initRepoWithBrokenRemote(repo).map { vcs =>
        ReleaseContext(
          state = TestSupport.dummyState(repo),
          vcs = Some(vcs),
          interactive = false
        )
      }
    }

  private val gitRepoWithCommitResource: Resource[IO, (File, io.release.vcs.Vcs)] =
    tempDirResource.evalMap { repo =>
      IO.blocking {
        TestSupport.initGitRepo(repo)
        sbt.IO.write(new File(repo, "file.txt"), "initial")
        TestSupport.commitAll(repo, "Initial commit")
        repo
      }.flatMap { initialized =>
        io.release.vcs.Vcs.detect(initialized).flatMap {
          case Some(vcs) => IO.pure((initialized, vcs))
          case None      =>
            IO.raiseError(
              new RuntimeException(s"Failed to detect VCS in ${initialized.getAbsolutePath}")
            )
        }
      }
    }

  private val gitRepoWithLoadedStateResource: Resource[IO, (File, sbt.State)] =
    gitRepoWithCommitResource.evalMap { case (repo, _) =>
      IO.blocking(repo -> loadedState(repo))
    }

  private def loadedState(repo: File, settings: Seq[Def.Setting[?]] = Nil): sbt.State =
    TestSupport.loadedState(
      repo,
      Seq(
        Project("root", repo).settings(
          (Seq(io.release.ReleaseIO.releaseIOIgnoreUntrackedFiles := false) ++ settings)*
        )
      ),
      currentProjectId = Some("root")
    )
}
