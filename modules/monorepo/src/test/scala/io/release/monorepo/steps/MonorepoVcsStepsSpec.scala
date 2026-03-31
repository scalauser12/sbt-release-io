package io.release.monorepo.steps

import cats.effect.IO
import cats.effect.Resource
import io.release.TestAssertions
import io.release.TestSupport
import io.release.internal.SbtRuntime
import io.release.monorepo.MonorepoContext
import io.release.monorepo.MonorepoReleaseIO
import io.release.monorepo.MonorepoSpecSupport
import io.release.monorepo.ProjectReleaseInfo
import io.release.monorepo.SelectionMode
import io.release.vcs.Vcs
import munit.CatsEffectSuite
import sbt.Def
import sbt.LocalProject
import sbt.Project
import sbt.State

import java.io.File

class MonorepoVcsStepsSpec extends CatsEffectSuite {

  test("initializeVcs.execute - detect Git from the loaded project base") {
    gitRepoWithLoadedStateResource().use { case (_, state) =>
      MonorepoVcsSteps.initializeVcs.execute(MonorepoContext(state = state)).map { result =>
        assertEquals(result.vcs.map(_.commandName), Some("git"))
      }
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

  test("tagReleasesPerProject.execute - overwrite the tag in interactive mode when confirmed") {
    perProjectTagContextResource.use { case (repo, project, baseCtx) =>
      val ctx = withFlags(baseCtx.copy(interactive = true), useDefaults = false)

      for {
        _       <- IO.blocking(TestSupport.runGit(repo, "tag", "core-v1.0.0"))
        _       <- IO.blocking {
                     sbt.IO.write(new File(repo, "file.txt"), "updated")
                     TestSupport.commitAll(repo, "Second commit")
                   }
        result  <- TestSupport.withInput("o\n") {
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
    "tagReleasesPerProject.execute - retry with a new tag name in interactive mode and store it"
  ) {
    perProjectTagContextResource.use { case (repo, project, baseCtx) =>
      val ctx = withFlags(baseCtx.copy(interactive = true), useDefaults = false)

      for {
        _              <- IO.blocking(TestSupport.runGit(repo, "tag", "core-v1.0.0"))
        originalTagRev <- IO.blocking(
                            TestSupport.runGit(repo, "rev-list", "-n", "1", "core-v1.0.0").trim
                          )
        _              <- IO.blocking {
                            sbt.IO.write(new File(repo, "file.txt"), "updated")
                            TestSupport.commitAll(repo, "Second commit")
                          }
        result         <- TestSupport.withInput("core-v1.0.1\n") {
                            MonorepoVcsSteps.tagReleasesPerProject.execute(ctx, project)
                          }
        oldTags        <- IO.blocking(TestSupport.runGit(repo, "tag", "--list", "core-v1.0.0"))
        newTags        <- IO.blocking(TestSupport.runGit(repo, "tag", "--list", "core-v1.0.1"))
        oldTagRev      <- IO.blocking(
                            TestSupport.runGit(repo, "rev-list", "-n", "1", "core-v1.0.0").trim
                          )
        newTagRev      <- IO.blocking(
                            TestSupport.runGit(repo, "rev-list", "-n", "1", "core-v1.0.1").trim
                          )
        headRev        <- IO.blocking(TestSupport.runGit(repo, "rev-parse", "HEAD").trim)
      } yield {
        assertEquals(oldTags.trim, "core-v1.0.0")
        assertEquals(newTags.trim, "core-v1.0.1")
        assertEquals(oldTagRev, originalTagRev)
        assertEquals(newTagRev, headRev)
        assertEquals(
          MonorepoSpecSupport.projectNamed(result.projects, "core").tagName,
          Some("core-v1.0.1")
        )
      }
    }
  }

  test(
    "tagReleasesPerProject.execute - abort in interactive mode when overwrite is declined"
  ) {
    perProjectTagContextResource.use { case (repo, project, baseCtx) =>
      val ctx = withFlags(baseCtx.copy(interactive = true), useDefaults = false)

      IO.blocking(TestSupport.runGit(repo, "tag", "core-v1.0.0")) *>
        TestAssertions.assertFailure[IllegalStateException, MonorepoContext](
          TestSupport.withInput("a\n") {
            MonorepoVcsSteps.tagReleasesPerProject.execute(ctx, project)
          }
        ) { err =>
          assertEquals(err.getMessage, "Tag [core-v1.0.0] already exists. Aborting release!")
        }
    }
  }

  test("preflightTags - report available status for a clean per-project tag path") {
    perProjectTagContextResource.use { case (_, _, ctx) =>
      MonorepoVcsSteps.preflightTags(ctx).map { outcomes =>
        assertEquals(
          outcomes,
          Seq(MonorepoVcsSteps.PreflightTagOutcome("core-v1.0.0", "available"))
        )
      }
    }
  }

  test("preflightTags - use the configured command name in tag conflict guidance") {
    perProjectTagContextResource.use { case (repo, _, baseCtx) =>
      val ctx = MonorepoSpecSupport.withPlan(
        baseCtx,
        MonorepoSpecSupport.releasePlan(
          selectionMode = SelectionMode.AllChanged,
          commandName = "releaseMonorepoCustom"
        )
      )

      IO.blocking(TestSupport.runGit(repo, "tag", "core-v1.0.0")) *>
        TestAssertions
          .assertFailure[IllegalStateException, Seq[MonorepoVcsSteps.PreflightTagOutcome]](
            MonorepoVcsSteps.preflightTags(ctx)
          ) { err =>
            assert(err.getMessage.contains("releaseMonorepoCustom help"))
            assert(!err.getMessage.contains("releaseIOMonorepo help"))
          }
    }
  }

  test("pushChanges.execute - fail during remote preflight before any push attempt") {
    brokenRemoteContextResource.use { ctx =>
      TestAssertions.assertFailure[IllegalStateException, MonorepoContext](
        MonorepoVcsSteps.pushChanges.execute(ctx)
      )(err => assert(err.getMessage.contains("Aborting the release due to remote check failure.")))
    }
  }

  test("pushChanges.validate - fail when VCS was not initialized by initializeVcs") {
    gitRepoWithLoadedStateResource().use { case (_, state) =>
      TestAssertions.assertFailure[IllegalStateException, Unit](
        MonorepoVcsSteps.pushChanges.validate(MonorepoContext(state = state))
      ) { err =>
        assertEquals(
          err.getMessage,
          "VCS not initialized. Ensure initializeVcs runs before this step."
        )
      }
    }
  }

  test("pushChanges.validate function value - fail when VCS was not initialized") {
    gitRepoWithLoadedStateResource().use { case (_, state) =>
      val validate = MonorepoVcsSteps.pushChanges.validate

      TestAssertions.assertFailure[IllegalStateException, Unit](
        validate(MonorepoContext(state = state))
      ) { err =>
        assertEquals(
          err.getMessage,
          "VCS not initialized. Ensure initializeVcs runs before this step."
        )
      }
    }
  }

  test("pushChanges.execute - fail when VCS was not initialized by initializeVcs") {
    gitRepoWithLoadedStateResource().use { case (_, state) =>
      TestAssertions.assertFailure[IllegalStateException, MonorepoContext](
        MonorepoVcsSteps.pushChanges.execute(MonorepoContext(state = state))
      ) { err =>
        assertEquals(
          err.getMessage,
          "VCS not initialized. Ensure initializeVcs runs before this step."
        )
      }
    }
  }

  private val tempDirResource: Resource[IO, File] =
    TestSupport.tempDirResource("monorepo-vcs-steps-spec")

  private val brokenRemoteContextResource: Resource[IO, MonorepoContext] =
    tempDirResource.evalMap { repo =>
      TestSupport.initRepoWithBrokenRemote(repo).map { vcs =>
        val state = loadedState(repo, Seq(rootProject(repo)))

        MonorepoContext(
          state = state,
          vcs = Some(vcs),
          interactive = false
        )
      }
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
              MonorepoReleaseIO.releaseIOMonorepoTagName    := ((name: String, ver: String) =>
                s"$name-v$ver"
              ),
              MonorepoReleaseIO.releaseIOMonorepoTagComment := ((name: String, ver: String) =>
                s"Release $name $ver"
              ),
              io.release.ReleaseIO.releaseIOVcsSign         := false
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
    TestSupport.loadedState(repo, projects, currentProjectId = Some("root"))

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
}
