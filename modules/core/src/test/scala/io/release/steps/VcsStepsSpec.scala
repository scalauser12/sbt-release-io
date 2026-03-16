package io.release.steps

import cats.effect.testing.specs2.CatsEffect
import cats.effect.{IO, Resource}
import io.release.vcs.Vcs
import io.release.{ReleaseContext, TestSupport}
import org.specs2.mutable.Specification

import java.io.File
import java.nio.file.Files
class VcsStepsSpec extends Specification with CatsEffect {

  "VcsSteps.pushChanges.validate" should {

    "allow validation to pass with a broken tracking remote when upstream is configured" in {
      releaseContextResource.use { ctx =>
        VcsSteps.pushChanges.validate(ctx).map { result =>
          result must beEqualTo(())
        }
      }
    }
  }

  "VcsSteps.pushChanges.execute" should {

    "fail during remote preflight in non-interactive mode before pushing" in {
      releaseContextResource.use { ctx =>
        VcsSteps.pushChanges.execute(ctx).attempt.map {
          case Left(err: IllegalStateException) =>
            err.getMessage must contain("Aborting the release due to remote check failure.")
          case other                            =>
            ko(s"Expected IllegalStateException but got $other")
        }
      }
    }
  }

  private val tempDirResource: Resource[IO, File] =
    Resource.make(IO.blocking(Files.createTempDirectory("vcs-steps-spec").toFile))(dir =>
      IO.blocking(TestSupport.deleteRecursively(dir))
    )

  private val releaseContextResource: Resource[IO, ReleaseContext] =
    tempDirResource.evalMap { repo =>
      initRepoWithBrokenRemote(repo).map { vcs =>
        ReleaseContext(
          state = TestSupport.dummyState(repo),
          vcs = Some(vcs),
          interactive = false
        )
      }
    }

  private def initRepoWithBrokenRemote(repo: File): IO[Vcs] = {
    IO.blocking {
      TestSupport.initGitRepo(repo)
      sbt.IO.write(new File(repo, "file.txt"), "initial")
      TestSupport.runGit(repo, "add", ".")
      TestSupport.runGit(repo, "commit", "-m", "Initial commit")
      TestSupport.runGit(repo, "branch", "-M", "main")
      TestSupport.runGit(
        repo,
        "remote",
        "add",
        "origin",
        new File(repo, "missing-remote.git").getAbsolutePath
      )
      TestSupport.runGit(repo, "config", "branch.main.remote", "origin")
      TestSupport.runGit(repo, "config", "branch.main.merge", "refs/heads/main")
      repo
    }.flatMap { r =>
      Vcs.detect(r).flatMap {
        case Some(vcs) => IO.pure(vcs)
        case None      =>
          IO.raiseError(new RuntimeException(s"Failed to detect VCS in ${r.getAbsolutePath}"))
      }
    }
  }

}
