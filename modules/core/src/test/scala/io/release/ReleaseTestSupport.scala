package io.release

import cats.effect.IO
import cats.effect.Resource
import io.release.vcs.Vcs
import sbt.Project
import sbt.Setting
import sbt.State

import java.io.File

/** Core-only test helpers layered on top of the shared `testkit` harness.
  *
  * These wrappers depend on core production types, so they stay in the core test sources to avoid
  * a project cycle between `core` and `testkit`.
  */
object ReleaseTestSupport {

  def loadedContextResource(
      prefix: String,
      buildSettings: Seq[Setting[?]] = Nil,
      currentProjectId: Option[String] = None
  )(projectsFor: File => Seq[Project]): Resource[IO, ReleaseContext] =
    TestSupport
      .loadedStateResource(
        prefix,
        buildSettings = buildSettings,
        currentProjectId = currentProjectId
      )(projectsFor)
      .map(state => ReleaseContext(state = state))

  def dummyContextResource(prefix: String): Resource[IO, ReleaseContext] =
    TestSupport.dummyStateResource(prefix).map(state => ReleaseContext(state = state))

  def gitRootState(
      repo: File,
      rootSettings: Seq[Setting[?]] = Nil
  ): State =
    TestSupport.loadedState(
      repo,
      Seq(
        Project("root", repo).settings(
          (Seq(
            ReleasePluginIO.autoImport.releaseIOVcsIgnoreUntrackedFiles := false,
            TestInteractionServiceCompat.interactionServiceSetting(
              TestSupport.StdinInteractionService
            )
          ) ++ rootSettings)*
        )
      ),
      currentProjectId = Some("root")
    )

  def gitRepoWithCommitResource(
      prefix: String,
      prepareRepo: File => IO[Unit] = repo =>
        IO.blocking(sbt.IO.write(new File(repo, "file.txt"), "initial")),
      commitMessage: String = "Initial commit"
  ): Resource[IO, (File, Vcs)] =
    TestSupport.gitRepoWithCommitResource(prefix, prepareRepo, commitMessage).evalMap { repo =>
      detectVcs(repo).map(repo -> _)
    }

  def gitRepoWithLoadedStateResource(
      prefix: String,
      rootSettings: Seq[Setting[?]] = Nil,
      prepareRepo: File => IO[Unit] = repo =>
        IO.blocking(sbt.IO.write(new File(repo, "file.txt"), "initial")),
      commitMessage: String = "Initial commit"
  ): Resource[IO, (File, State)] =
    gitRepoWithCommitResource(prefix, prepareRepo, commitMessage).evalMap { case (repo, _) =>
      IO.blocking(repo -> gitRootState(repo, rootSettings))
    }

  def detectVcs(repo: File): IO[Vcs] =
    Vcs.detect(repo).flatMap {
      case Some(vcs) => IO.pure(vcs)
      case None      =>
        IO.raiseError(new RuntimeException(s"Failed to detect VCS in ${repo.getAbsolutePath}"))
    }

  def brokenRemoteContextResource(
      prefix: String,
      interactive: Boolean = false
  ): Resource[IO, ReleaseContext] =
    TestSupport.tempDirResource(prefix).evalMap { repo =>
      initRepoWithBrokenRemote(repo).map { vcs =>
        ReleaseContext(
          state = gitRootState(repo),
          vcs = Some(vcs),
          interactive = interactive
        )
      }
    }

  private def initRepoWithBrokenRemote(repo: File): IO[Vcs] =
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
    }.flatMap(detectVcs)
}
