package io.release.monorepo

import cats.effect.IO
import cats.effect.Resource
import io.release.TestAssertions.assertFailure
import io.release.TestSupport
import io.release.monorepo.steps.MonorepoReleaseSteps
import io.release.monorepo.steps.MonorepoVcsSteps
import munit.CatsEffectSuite

import java.io.File

class MonorepoPreflightSpec extends CatsEffectSuite {

  test("renderSummary - include selection, mode, and per-project tag summaries") {
    val summary = MonorepoPreflight.Summary(
      selectionMode = SelectionMode.ExplicitSelection,
      versionMode = "per-project",
      tagStrategy = MonorepoTagStrategy.PerProject,
      projects = Seq(
        MonorepoPreflight.ProjectSummary(
          name = "core",
          releaseVersion = "1.0.0",
          nextVersion = "1.1.0-SNAPSHOT",
          tagName = "core/v1.0.0",
          tagStatus = "available"
        )
      ),
      crossBuildEnabled = false,
      publishSummary = "enabled",
      pushSummary = "configured (not executed in check mode)"
    )

    val lines = MonorepoPreflight.renderSummary(summary)

    assert(lines.exists(_.contains("selection mode: explicit selection")))
    assert(lines.exists(_.contains("version mode  : per-project")))
    assert(lines.exists(_.contains("tag strategy  : per-project")))
    assert(lines.exists(_.contains("publish       : enabled")))
    assert(lines.exists(_.contains("push          : configured (not executed in check mode)")))
    assert(lines.exists(_.contains("core: release 1.0.0, next 1.1.0-SNAPSHOT")))
    assert(lines.exists(_.contains("tag core/v1.0.0 (available)")))
  }

  test("helpLines - describe no release side effects and the cross-build caveat") {
    val lines = MonorepoPreflight.helpLines("releaseIOMonorepo")

    assert(
      lines.exists(
        _.contains(
          "No release side effects: no version-file writes, commits, tags, publish, or push"
        )
      )
    )
    assert(
      lines.exists(
        _.contains(
          "may temporarily switch Scala versions during validation and then restore the entry version"
        )
      )
    )
    assert(lines.exists(_.contains(io.release.internal.HelpDocsLinks.MonorepoReadme)))
    assert(lines.exists(_.contains(io.release.internal.HelpDocsLinks.MonorepoUsage)))
  }

  test("check - resolve explicit selection without mutating files or tags") {
    preflightFixtureResource.use { case (repo, ctx, versionFile) =>
      for {
        beforeVersion <- IO.blocking(sbt.IO.read(versionFile))
        beforeTags    <- IO.blocking(TestSupport.runGit(repo, "tag", "--list"))
        summary       <- MonorepoPreflight.check(
                           ctx,
                           Seq(MonorepoReleaseSteps.checkCleanWorkingDir),
                           crossBuild = false
                         )
        afterVersion  <- IO.blocking(sbt.IO.read(versionFile))
        afterTags     <- IO.blocking(TestSupport.runGit(repo, "tag", "--list"))
      } yield {
        assertEquals(summary.selectionMode, SelectionMode.ExplicitSelection)
        assertEquals(summary.versionMode, "per-project")
        assertEquals(summary.tagStrategy, MonorepoTagStrategy.PerProject)
        assertEquals(summary.projects.map(_.name), Seq("core"))
        assertEquals(summary.projects.map(_.releaseVersion), Seq("0.1.0"))
        assertEquals(summary.projects.map(_.nextVersion), Seq("0.2.0-SNAPSHOT"))
        assertEquals(summary.projects.map(_.tagName), Seq("core/v0.1.0"))
        assertEquals(summary.projects.map(_.tagStatus), Seq("available"))
        assertEquals(summary.publishSummary, "step not configured")
        assertEquals(summary.pushSummary, "step not configured")
        assertEquals(beforeVersion, afterVersion)
        assertEquals(beforeTags.trim, afterTags.trim)
      }
    }
  }

  test("renderProjects - fail on inconsistent project and tag outcome counts") {
    val projects = Seq(
      MonorepoTestSupport.dummyProject("core").copy(versions = Some("1.0.0" -> "1.1.0-SNAPSHOT")),
      MonorepoTestSupport.dummyProject("api").copy(versions = Some("2.0.0" -> "2.1.0-SNAPSHOT"))
    )
    val tags     = Seq(
      MonorepoVcsSteps.PreflightTagOutcome("core/v1.0.0", "available"),
      MonorepoVcsSteps.PreflightTagOutcome("api/v2.0.0", "available"),
      MonorepoVcsSteps.PreflightTagOutcome("extra/v3.0.0", "available")
    )

    assertFailure[IllegalStateException, Seq[MonorepoPreflight.ProjectSummary]](
      MonorepoPreflight.renderProjects(projects, tags)
    )(err => assert(err.getMessage.contains("inconsistent project/tag counts")))
  }

  private val preflightFixtureResource: Resource[IO, (File, MonorepoContext, File)] =
    TestSupport.tempDirResource("monorepo-preflight-spec").evalMap { repo =>
      IO.blocking {
        val coreBase    = new File(repo, "core")
        coreBase.mkdirs()
        val versionFile = new File(coreBase, "version.sbt")
        sbt.IO.write(new File(repo, "tracked.txt"), "initial")
        sbt.IO.write(versionFile, """version := "0.1.0-SNAPSHOT"""" + "\n")

        TestSupport.initGitRepo(repo)
        TestSupport.commitAll(repo, "Initial commit")

        val projects = Seq(
          MonorepoSpecSupport.monorepoRootProject(
            repo,
            projectIds = Seq("core"),
            settings = Seq(
              io.release.ReleaseIO.releaseIOIgnoreUntrackedFiles := true
            )
          ),
          MonorepoSpecSupport.versionedProject(
            "core",
            coreBase,
            settings = Seq(
              io.release.ReleaseIO.releaseIOVersion     := ((version: String) =>
                version.stripSuffix("-SNAPSHOT")
              ),
              io.release.ReleaseIO.releaseIONextVersion := ((_: String) => "0.2.0-SNAPSHOT")
            )
          )
        )
        val state    = TestSupport.loadedState(repo, projects, currentProjectId = Some("root"))
        val ctx      = MonorepoContext(state = state, interactive = false).withReleasePlan(
          MonorepoSpecSupport.releasePlan(
            selectionMode = SelectionMode.ExplicitSelection,
            selectedNames = Seq("core")
          )
        )

        (repo, ctx, versionFile)
      }
    }
}
