package io.release.steps

import cats.effect.testing.specs2.CatsEffect
import cats.effect.{IO, Resource}
import io.release.{ReleaseContext, TestSupport}
import org.specs2.mutable.Specification
import sbtrelease.Vcs

import java.io.File
import java.nio.file.Files
import scala.sys.process.Process

class VcsStepsSpec extends Specification with CatsEffect {

  "VcsSteps.pushChanges.check" should {

    "allow check phase to pass with a broken tracking remote when upstream is configured" in {
      releaseContextResource.use { ctx =>
        VcsSteps.pushChanges.check(ctx).map { result =>
          result.state.configuration.baseDirectory() must_== ctx.state.configuration.baseDirectory()
        }
      }
    }
  }

  "VcsSteps.pushChanges.action" should {

    "fail during remote preflight in non-interactive mode before pushing" in {
      releaseContextResource.use { ctx =>
        VcsSteps.pushChanges.action(ctx).attempt.map {
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
      IO.blocking {
        val vcs = initRepoWithBrokenRemote(repo)
        ReleaseContext(
          state = TestSupport.dummyState(repo),
          vcs = Some(vcs),
          interactive = false
        )
      }
    }

  private def initRepoWithBrokenRemote(repo: File): Vcs = {
    initGitRepo(repo)
    sbt.IO.write(new File(repo, "file.txt"), "initial")
    runGit(repo, "add", ".")
    runGit(repo, "commit", "-m", "Initial commit")
    runGit(repo, "branch", "-M", "main")
    runGit(repo, "remote", "add", "origin", new File(repo, "missing-remote.git").getAbsolutePath)
    runGit(repo, "config", "branch.main.remote", "origin")
    runGit(repo, "config", "branch.main.merge", "refs/heads/main")
    Vcs
      .detect(repo)
      .getOrElse(sys.error(s"Failed to detect VCS in ${repo.getAbsolutePath}"))
  }

  private def initGitRepo(repo: File): Unit = {
    runGit(repo, "init")
    runGit(repo, "config", "user.email", "test@example.com")
    runGit(repo, "config", "user.name", "Test User")
    ()
  }

  private def runGit(repo: File, args: String*): String =
    Process(Seq("git") ++ args, repo).!!
}
