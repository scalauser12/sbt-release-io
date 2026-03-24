package io.release.monorepo.steps

import cats.effect.IO
import cats.effect.Ref
import cats.effect.Resource
import io.release.TestAssertions
import io.release.TestSupport
import io.release.internal.SbtRuntime
import io.release.monorepo.MonorepoContext
import io.release.monorepo.MonorepoReleaseIO
import io.release.monorepo.MonorepoSpecSupport
import io.release.monorepo.MonorepoTagStrategy
import io.release.monorepo.ProjectReleaseInfo
import io.release.monorepo.SelectionMode
import io.release.vcs.Vcs
import munit.CatsEffectSuite
import sbt.Def
import sbt.LocalProject
import sbt.Project
import sbt.ProjectRef
import sbt.State

import java.io.ByteArrayInputStream
import java.io.File
import java.io.InputStream
import java.nio.charset.StandardCharsets
import java.util.concurrent.Semaphore

class MonorepoVcsStepsSpec extends CatsEffectSuite {

  test("initializeVcs.execute - detect Git from the loaded project base") {
    gitRepoWithLoadedStateResource().use { case (_, state) =>
      MonorepoVcsSteps.initializeVcs.execute(MonorepoContext(state = state)).map { result =>
        assertEquals(result.vcs.map(_.commandName), Some("git"))
      }
    }
  }

  test("checkCleanWorkingDir.validate - succeed for a clean loaded repo") {
    gitRepoWithLoadedStateResource().use { case (_, state) =>
      MonorepoVcsSteps.checkCleanWorkingDir.validate(MonorepoContext(state = state))
    }
  }

  test("checkCleanWorkingDir.validate - fail for a dirty tracked file in a loaded repo") {
    gitRepoWithLoadedStateResource().use { case (repo, state) =>
      IO.blocking(sbt.IO.write(new File(repo, "file.txt"), "modified")) *>
        TestAssertions.assertFailure[IllegalStateException, Unit](
          MonorepoVcsSteps.checkCleanWorkingDir.validate(MonorepoContext(state = state))
        ) { err =>
          assert(err.getMessage.contains("unstaged modified files"))
          assert(err.getMessage.contains("file.txt"))
        }
    }
  }

  test(
    "checkCleanWorkingDir.validate - fail for an untracked file when ignoreUntrackedFiles is false"
  ) {
    gitRepoWithLoadedStateResource(rootSettings =
      Seq(io.release.ReleaseIO.releaseIOIgnoreUntrackedFiles := false)
    )
      .use { case (repo, state) =>
        IO.blocking(sbt.IO.write(new File(repo, "untracked.txt"), "new")) *>
          TestAssertions.assertFailure[IllegalStateException, Unit](
            MonorepoVcsSteps.checkCleanWorkingDir.validate(MonorepoContext(state = state))
          ) { err =>
            assert(err.getMessage.contains("untracked files"))
            assert(err.getMessage.contains("untracked.txt"))
          }
      }
  }

  test("checkCleanWorkingDir.validate - allow untracked files when ignoreUntrackedFiles is true") {
    gitRepoWithLoadedStateResource(rootSettings =
      Seq(io.release.ReleaseIO.releaseIOIgnoreUntrackedFiles := true)
    )
      .use { case (repo, state) =>
        IO.blocking(sbt.IO.write(new File(repo, "untracked.txt"), "new")) *>
          MonorepoVcsSteps.checkCleanWorkingDir.validate(MonorepoContext(state = state))
      }
  }

  test("pushChanges.validate - pass with a broken tracking remote when upstream is configured") {
    brokenRemoteContextResource.use { ctx =>
      MonorepoVcsSteps.pushChanges.validate(ctx)
    }
  }

  test("pushChanges.execute - fail during remote preflight before any push attempt") {
    brokenRemoteContextResource.use { ctx =>
      TestAssertions.assertFailure[IllegalStateException, MonorepoContext](
        MonorepoVcsSteps.pushChanges.execute(ctx)
      )(err => assert(err.getMessage.contains("Aborting the release due to remote check failure.")))
    }
  }

  test("tagReleasesPerProject.execute - create the tag and keep the resulting context usable") {
    perProjectTagContextResource.use { case (repo, project, ctx) =>
      for {
        result <- MonorepoVcsSteps.tagReleasesPerProject.execute(ctx, project)
        _      <- MonorepoVcsSteps.checkCleanWorkingDir.validate(result)
        tags   <- IO.blocking(TestSupport.runGit(repo, "tag", "--list", "core-v1.0.0"))
      } yield {
        assertEquals(tags.trim, "core-v1.0.0")
        assertEquals(
          MonorepoSpecSupport.projectNamed(result.projects, "core").tagName,
          Some("core-v1.0.0")
        )
        assertEquals(result.vcs.map(_.commandName), Some("git"))
      }
    }
  }

  test(
    "tagReleasesPerProject.execute - abort in non-interactive mode when the tag already exists"
  ) {
    perProjectTagContextResource.use { case (repo, project, ctx) =>
      IO.blocking(TestSupport.runGit(repo, "tag", "core-v1.0.0")) *>
        TestAssertions.assertFailure[IllegalStateException, MonorepoContext](
          MonorepoVcsSteps.tagReleasesPerProject.execute(ctx, project)
        ) { err =>
          assert(err.getMessage.contains("Tag [core-v1.0.0] already exists"))
          assert(err.getMessage.contains("non-interactive mode"))
        }
    }
  }

  test(
    "tagReleasesPerProject.execute - overwrite the tag in interactive mode when the user confirms"
  ) {
    perProjectTagContextResource.use { case (repo, project, baseCtx) =>
      val ctx = withFlags(baseCtx.copy(interactive = true), useDefaults = false)

      for {
        _       <- IO.blocking(TestSupport.runGit(repo, "tag", "core-v1.0.0"))
        _       <- IO.blocking {
                     sbt.IO.write(new File(repo, "file.txt"), "updated")
                     TestSupport.commitAll(repo, "Second commit")
                   }
        result  <- withInput("y\n") {
                     MonorepoVcsSteps.tagReleasesPerProject.execute(ctx, project)
                   }
        tagRev  <- IO.blocking(TestSupport.runGit(repo, "rev-list", "-n", "1", "core-v1.0.0").trim)
        headRev <- IO.blocking(TestSupport.runGit(repo, "rev-parse", "HEAD").trim)
      } yield {
        assertEquals(tagRev, headRev)
        assertEquals(
          MonorepoSpecSupport.projectNamed(result.projects, "core").tagName,
          Some("core-v1.0.0")
        )
      }
    }
  }

  test(
    "tagReleasesPerProject.execute - abort in interactive mode when the user declines overwrite"
  ) {
    perProjectTagContextResource.use { case (repo, project, baseCtx) =>
      val ctx = withFlags(baseCtx.copy(interactive = true), useDefaults = false)

      IO.blocking(TestSupport.runGit(repo, "tag", "core-v1.0.0")) *>
        TestAssertions.assertFailure[IllegalStateException, MonorepoContext](
          withInput("n\n") {
            MonorepoVcsSteps.tagReleasesPerProject.execute(ctx, project)
          }
        ) { err =>
          assertEquals(err.getMessage, "Tag [core-v1.0.0] already exists for core. Aborting.")
        }
    }
  }

  test("tagReleasesUnified.execute - create the unified tag and update every project") {
    unifiedTagContextResource.use { case (repo, ctx) =>
      for {
        result <- MonorepoVcsSteps.tagReleasesUnified.execute(ctx)
        _      <- MonorepoVcsSteps.checkCleanWorkingDir.validate(result)
        tags   <- IO.blocking(TestSupport.runGit(repo, "tag", "--list", "v1.0.0"))
      } yield {
        assertEquals(tags.trim, "v1.0.0")
        assert(result.projects.forall(_.tagName.contains("v1.0.0")))
        assertEquals(result.vcs.map(_.commandName), Some("git"))
      }
    }
  }

  test(
    "tagReleasesUnified.execute - abort in non-interactive mode when the unified tag already exists"
  ) {
    unifiedTagContextResource.use { case (repo, ctx) =>
      IO.blocking(TestSupport.runGit(repo, "tag", "v1.0.0")) *>
        TestAssertions.assertFailure[IllegalStateException, MonorepoContext](
          MonorepoVcsSteps.tagReleasesUnified.execute(ctx)
        ) { err =>
          assert(err.getMessage.contains("Tag [v1.0.0] already exists for release."))
          assert(err.getMessage.contains("non-interactive mode"))
        }
    }
  }

  test(
    "pushChanges.execute - abort when the interactive user declines after a remote check failure"
  ) {
    pushPromptExecutionResource(
      checkRemote0 = IO.pure(1),
      useDefaults = false,
      input = Some("n\n")
    ).use { case (run, pushes) =>
      TestAssertions.assertFailure[IllegalStateException, MonorepoContext](
        run
      ) { err =>
        assert(err.getMessage.contains("Aborting the release due to remote check failure."))
      } *> pushes.get.map(count => assertEquals(count, 0))
    }
  }

  test(
    "pushChanges.execute - continue after a remote check failure when the interactive user confirms"
  ) {
    pushPromptExecutionResource(
      checkRemote0 = IO.pure(1),
      useDefaults = false,
      input = Some("y\ny\n")
    ).use { case (run, pushes) =>
      run *> pushes.get.map(count => assertEquals(count, 1))
    }
  }

  test(
    "pushChanges.execute - skip the push when the interactive user declines at the push prompt"
  ) {
    pushPromptExecutionResource(
      checkRemote0 = IO.pure(0),
      useDefaults = false,
      input = Some("n\n")
    ).use { case (run, pushes) =>
      run.flatMap { result =>
        pushes.get.map { count =>
          assertEquals(count, 0)
          assertEquals(result.vcs.map(_.commandName), Some("stub"))
        }
      }
    }
  }

  test("pushChanges.execute - auto-push in interactive use-defaults mode") {
    pushPromptExecutionResource(
      checkRemote0 = IO.pure(0),
      useDefaults = true
    ).use { case (run, pushes) =>
      run *> pushes.get.map(count => assertEquals(count, 1))
    }
  }

  private val tempDirResource: Resource[IO, File] =
    TestSupport.tempDirResource("monorepo-vcs-steps-spec")

  private val stdinLock = new Semaphore(1)

  private val brokenRemoteContextResource: Resource[IO, MonorepoContext] =
    tempDirResource.evalMap { repo =>
      TestSupport.initRepoWithBrokenRemote(repo).map { vcs =>
        MonorepoContext(
          state = TestSupport.dummyState(repo),
          vcs = Some(vcs),
          interactive = false
        )
      }
    }

  private def pushPromptContextResource(
      checkRemote0: IO[Int],
      useDefaults: Boolean
  ): Resource[IO, (MonorepoContext, Ref[IO, Int])] =
    tempDirResource.evalMap { repo =>
      for {
        pushes <- Ref[IO].of(0)
        vcs     = new RecordingVcs(
                    baseDir = repo,
                    pushCounter = pushes,
                    checkRemote0 = checkRemote0
                  )
        ctx     = withFlags(
                    MonorepoContext(
                      state = TestSupport.dummyState(repo),
                      vcs = Some(vcs),
                      interactive = true
                    ),
                    useDefaults = useDefaults
                  )
      } yield ctx -> pushes
    }

  private def pushPromptExecutionResource(
      checkRemote0: IO[Int],
      useDefaults: Boolean,
      input: Option[String] = None
  ): Resource[IO, (IO[MonorepoContext], Ref[IO, Int])] =
    pushPromptContextResource(checkRemote0, useDefaults).map { case (ctx, pushes) =>
      val run = input match {
        case Some(chars) => withInput(chars) { MonorepoVcsSteps.pushChanges.execute(ctx) }
        case None        => MonorepoVcsSteps.pushChanges.execute(ctx)
      }
      run -> pushes
    }

  private def gitRepoWithLoadedStateResource(
      rootSettings: Seq[Def.Setting[?]] = Seq(
        io.release.ReleaseIO.releaseIOIgnoreUntrackedFiles := false
      )
  ): Resource[IO, (File, State)] =
    gitRepoWithVcsResource { repo =>
      sbt.IO.write(new File(repo, "file.txt"), "initial")
    }.evalMap { case (repo, _) =>
      IO.blocking(repo -> loadedState(repo, Seq(rootProject(repo, settings = rootSettings))))
    }

  private val perProjectTagContextResource
      : Resource[IO, (File, ProjectReleaseInfo, MonorepoContext)] =
    gitRepoWithVcsResource { repo =>
      sbt.IO.write(new File(repo, "file.txt"), "initial")
      val coreBase = new File(repo, "core")
      coreBase.mkdirs()
      sbt.IO.write(new File(coreBase, "version.sbt"), """version := "1.0.0-SNAPSHOT"""" + "\n")
    }.evalMap { case (repo, vcs) =>
      IO.blocking {
        val coreBase = new File(repo, "core")
        val projects = Seq(
          rootProject(
            repo,
            aggregateIds = Seq("core"),
            settings = Seq(
              MonorepoReleaseIO.releaseIOMonorepoTagStrategy       := MonorepoTagStrategy.PerProject,
              MonorepoReleaseIO.releaseIOMonorepoTagName           := ((name: String, ver: String) =>
                s"$name-v$ver"
              ),
              MonorepoReleaseIO.releaseIOMonorepoUnifiedTagName    := ((ver: String) => s"v$ver"),
              MonorepoReleaseIO.releaseIOMonorepoTagComment        := ((name: String, ver: String) =>
                s"Release $name $ver"
              ),
              MonorepoReleaseIO.releaseIOMonorepoUnifiedTagComment := ((summary: String) =>
                s"Release: $summary"
              ),
              io.release.ReleaseIO.releaseIOVcsSign                := false
            )
          ),
          Project("core", coreBase)
        )
        val state    = loadedState(repo, projects)
        val project  = projectInfo(
          state,
          projects,
          "core",
          versions = Some("1.0.0" -> "1.1.0-SNAPSHOT")
        )

        (
          repo,
          project,
          MonorepoContext(
            state = state,
            vcs = Some(vcs),
            interactive = false,
            projects = Seq(project)
          )
        )
      }
    }

  private val unifiedTagContextResource: Resource[IO, (File, MonorepoContext)] =
    gitRepoWithVcsResource { repo =>
      sbt.IO.write(new File(repo, "file.txt"), "initial")
      val coreBase = new File(repo, "core")
      val apiBase  = new File(repo, "api")
      coreBase.mkdirs()
      apiBase.mkdirs()
      sbt.IO.write(new File(coreBase, "version.sbt"), """version := "1.0.0-SNAPSHOT"""" + "\n")
      sbt.IO.write(new File(apiBase, "version.sbt"), """version := "1.0.0-SNAPSHOT"""" + "\n")
    }.evalMap { case (repo, vcs) =>
      IO.blocking {
        val coreBase = new File(repo, "core")
        val apiBase  = new File(repo, "api")
        val projects = Seq(
          rootProject(
            repo,
            aggregateIds = Seq("core", "api"),
            settings = Seq(
              MonorepoReleaseIO.releaseIOMonorepoTagStrategy       := MonorepoTagStrategy.Unified,
              MonorepoReleaseIO.releaseIOMonorepoTagName           := ((name: String, ver: String) =>
                s"$name-v$ver"
              ),
              MonorepoReleaseIO.releaseIOMonorepoUnifiedTagName    := ((ver: String) => s"v$ver"),
              MonorepoReleaseIO.releaseIOMonorepoTagComment        := ((name: String, ver: String) =>
                s"Release $name $ver"
              ),
              MonorepoReleaseIO.releaseIOMonorepoUnifiedTagComment := ((summary: String) =>
                s"Release: $summary"
              ),
              io.release.ReleaseIO.releaseIOVcsSign                := false
            )
          ),
          Project("core", coreBase),
          Project("api", apiBase)
        )
        val state    = loadedState(repo, projects)
        val versions = Some("1.0.0" -> "1.1.0-SNAPSHOT")
        val ctx      = MonorepoContext(
          state = state,
          vcs = Some(vcs),
          interactive = false,
          tagStrategy = MonorepoTagStrategy.Unified,
          projects = Seq(
            projectInfo(state, projects, "core", versions),
            projectInfo(state, projects, "api", versions)
          )
        )

        repo -> ctx
      }
    }

  private def gitRepoWithVcsResource(
      prepareRepo: File => Unit
  ): Resource[IO, (File, Vcs)] =
    tempDirResource.evalMap { repo =>
      IO.blocking {
        TestSupport.initGitRepo(repo)
        prepareRepo(repo)
        TestSupport.commitAll(repo, "Initial commit")
        repo
      }.flatMap { initialized =>
        Vcs.detect(initialized).flatMap {
          case Some(vcs) => IO.pure((initialized, vcs))
          case None      =>
            IO.raiseError(
              new RuntimeException(s"Failed to detect VCS in ${initialized.getAbsolutePath}")
            )
        }
      }
    }

  private def loadedState(repo: File, projects: Seq[Project]): State =
    TestSupport.loadedState(
      repo,
      projects,
      currentProjectId = Some("root")
    )

  private def rootProject(
      repo: File,
      aggregateIds: Seq[String] = Nil,
      settings: Seq[Def.Setting[?]] = Nil
  ): Project = {
    val aggregated =
      if (aggregateIds.nonEmpty)
        Project("root", repo).aggregate(aggregateIds.map(LocalProject(_))*)
      else Project("root", repo)

    aggregated.settings(
      (Seq(io.release.ReleaseIO.releaseIOIgnoreUntrackedFiles := false) ++ settings)*
    )
  }

  private def projectInfo(
      state: State,
      projects: Seq[Project],
      id: String,
      versions: Option[(String, String)] = None
  ): ProjectReleaseInfo = {
    val refsById =
      SbtRuntime.extracted(state).structure.allProjectRefs.map(ref => ref.project -> ref).toMap

    projects.find(_.id == id) match {
      case Some(project) =>
        ProjectReleaseInfo(
          ref = refsById.getOrElse(id, fail(s"Expected loaded ProjectRef for '$id'")),
          name = id,
          baseDir = project.base,
          versionFile = new File(project.base, "version.sbt"),
          versions = versions
        )
      case None          =>
        fail(s"Expected project '$id'")
    }
  }

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

  private def withFlags(
      ctx: MonorepoContext,
      useDefaults: Boolean
  ): MonorepoContext =
    MonorepoSpecSupport.withPlan(
      ctx,
      MonorepoSpecSupport.releasePlan(
        selectionMode = SelectionMode.AllChanged,
        flags = MonorepoSpecSupport.defaultFlags.copy(
          useDefaults = useDefaults,
          interactive = ctx.interactive
        )
      )
    )

  private final class RecordingVcs(
      val baseDir: File,
      pushCounter: Ref[IO, Int],
      checkRemote0: IO[Int]
  ) extends Vcs {
    override def commandName: String = "stub"

    override def currentHash: IO[String] = IO.pure("deadbeef")

    override def currentBranch: IO[String] = IO.pure("main")

    override def trackingRemote: IO[String] = IO.pure("origin")

    override def hasUpstream: IO[Boolean] = IO.pure(true)

    override def isBehindRemote: IO[Boolean] = IO.pure(false)

    override def existsTag(name: String): IO[Boolean] = IO.pure(false)

    override def modifiedFiles: IO[Seq[String]] = IO.pure(Seq.empty)

    override def stagedFiles: IO[Seq[String]] = IO.pure(Seq.empty)

    override def untrackedFiles: IO[Seq[String]] = IO.pure(Seq.empty)

    override def status: IO[String] = IO.pure("")

    override def checkRemote(remote: String): IO[Int] = checkRemote0

    override def add(files: String*): IO[Unit] = IO.unit

    override def commit(message: String, sign: Boolean, signOff: Boolean): IO[Unit] = IO.unit

    override def tag(name: String, comment: String, sign: Boolean, force: Boolean): IO[Unit] =
      IO.unit

    override def pushChanges: IO[Unit] =
      pushCounter.update(_ + 1)
  }
}
