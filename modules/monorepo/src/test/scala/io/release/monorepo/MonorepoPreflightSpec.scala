package io.release.monorepo

import cats.effect.IO
import cats.effect.Resource
import io.release.TestAssertions.assertFailure
import io.release.TestSupport
import io.release.internal.HelpDocsLinks
import io.release.monorepo.steps.MonorepoReleaseSteps
import io.release.monorepo.steps.MonorepoVcsSteps
import munit.CatsEffectSuite

import java.io.File

class MonorepoPreflightSpec extends CatsEffectSuite {

  test("renderSummary - include selection and per-project tag summaries") {
    val summary = MonorepoPreflight.Summary(
      selectionMode = MonorepoPreflight.Evaluation.Resolved(SelectionMode.ExplicitSelection),
      projects = Seq(
        MonorepoPreflight.ProjectSummary(
          name = "core",
          versions = MonorepoPreflight.Evaluation.Resolved(
            MonorepoPreflight.ProjectVersions("1.0.0", "1.1.0-SNAPSHOT")
          ),
          tag = MonorepoPreflight.Evaluation.Resolved(
            MonorepoPreflight.ProjectTag("core/v1.0.0", "available")
          )
        )
      ),
      crossBuildEnabled = false,
      publishSummary = "enabled",
      pushSummary = "configured (not executed in check mode)",
      stepNames = Seq("detect-or-select-projects", "check-clean-working-dir", "tag-releases")
    )

    val lines = MonorepoPreflight.renderSummary(summary)

    assert(lines.exists(_.contains("selection mode: explicit selection")))
    assert(lines.exists(_.contains("publish       : enabled")))
    assert(lines.exists(_.contains("push          : configured (not executed in check mode)")))
    assert(lines.exists(_.contains("core: release 1.0.0, next 1.1.0-SNAPSHOT")))
    assert(lines.exists(_.contains("tag core/v1.0.0 (available)")))
  }

  test("helpLines - describe per-project override syntax and check-mode caveats") {
    val lines = MonorepoPreflight.helpLines("releaseIOMonorepo")

    assert(lines.exists(_.contains("release-version <project>=<version>")))
    assert(lines.exists(_.contains("next-version <project>=<version>")))
    assert(
      lines.exists(
        _.contains("Use project <id> when a project id collides with a CLI keyword or subcommand")
      )
    )
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
    assert(lines.exists(_.contains(HelpDocsLinks.MonorepoReadme)))
    assert(lines.exists(_.contains(HelpDocsLinks.MonorepoUsage)))
  }

  test("check - resolve explicit selection, versions, and tags without mutating files or tags") {
    preflightFixtureResource.use { case (repo, ctx, versionFile) =>
      val session = MonorepoPreparedSession(ctx.state, ctx.releasePlan.get, ctx)

      for {
        beforeVersion <- IO.blocking(sbt.IO.read(versionFile))
        beforeTags    <- IO.blocking(TestSupport.runGit(repo, "tag", "--list"))
        summary       <- MonorepoPreflight.check(
                           session,
                           Seq(
                             MonorepoReleaseSteps.checkCleanWorkingDir,
                             MonorepoReleaseSteps.detectOrSelectProjects,
                             MonorepoReleaseSteps.inquireVersions,
                             MonorepoReleaseSteps.tagReleases
                           )
                         )
        afterVersion  <- IO.blocking(sbt.IO.read(versionFile))
        afterTags     <- IO.blocking(TestSupport.runGit(repo, "tag", "--list"))
      } yield {
        assertEquals(
          summary.selectionMode,
          MonorepoPreflight.Evaluation.Resolved(SelectionMode.ExplicitSelection)
        )
        assertEquals(summary.projects.map(_.name), Seq("core"))
        assertEquals(
          summary.projects.map(_.versions),
          Seq(
            MonorepoPreflight.Evaluation.Resolved(
              MonorepoPreflight.ProjectVersions("0.1.0", "0.2.0-SNAPSHOT")
            )
          )
        )
        assertEquals(
          summary.projects.map(_.tag),
          Seq(
            MonorepoPreflight.Evaluation.Resolved(
              MonorepoPreflight.ProjectTag("core/v0.1.0", "available")
            )
          )
        )
        assertEquals(summary.publishSummary, "step not configured")
        assertEquals(summary.pushSummary, "step not configured")
        assertEquals(
          summary.stepNames,
          Seq(
            "check-clean-working-dir",
            "detect-or-select-projects",
            "inquire-versions",
            "tag-releases"
          )
        )
        assertEquals(beforeVersion, afterVersion)
        assertEquals(beforeTags.trim, afterTags.trim)
      }
    }
  }

  test(
    "check - render selection, versions, and tags as not evaluated when the check process omits the built-in steps"
  ) {
    preflightFixtureResource.use { case (_, ctx, _) =>
      val session = MonorepoPreparedSession(ctx.state, ctx.releasePlan.get, ctx)

      MonorepoPreflight
        .check(
          session,
          Seq(MonorepoReleaseSteps.checkCleanWorkingDir)
        )
        .map { summary =>
          assertEquals(
            summary.selectionMode,
            MonorepoPreflight.Evaluation.NotEvaluated(
              "detect-or-select-projects not in check process"
            )
          )
          assertEquals(summary.projects.map(_.name), Seq("core"))
          assertEquals(
            summary.projects.map(_.versions),
            Seq(MonorepoPreflight.Evaluation.NotEvaluated("inquire-versions not in check process"))
          )
          assertEquals(
            summary.projects.map(_.tag),
            Seq(MonorepoPreflight.Evaluation.NotEvaluated("tag-releases not in check process"))
          )
        }
    }
  }

  test("renderProjects - fail on inconsistent project and tag outcome counts") {
    val projects = Seq(
      MonorepoTestSupport.dummyProject("core").copy(versions = Some("1.0.0" -> "1.1.0-SNAPSHOT")),
      MonorepoTestSupport.dummyProject("api").copy(versions = Some("2.0.0" -> "2.1.0-SNAPSHOT"))
    )
    val tags     = MonorepoPreflight.Evaluation.Resolved(
      Seq(
        MonorepoVcsSteps.PreflightTagOutcome("core/v1.0.0", "available"),
        MonorepoVcsSteps.PreflightTagOutcome("api/v2.0.0", "available"),
        MonorepoVcsSteps.PreflightTagOutcome("extra/v3.0.0", "available")
      )
    )

    assertFailure[IllegalStateException, Seq[MonorepoPreflight.ProjectSummary]](
      MonorepoPreflight.renderProjects(
        projects,
        builtInVersionsResolved = true,
        tagOutcomes = tags
      )
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
            settings = Seq(io.release.ReleaseIO.releaseIOIgnoreUntrackedFiles := true)
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
        val current  = Seq(
          MonorepoProjectResolver.resolveAll(state).unsafeRunSync().find(_.name == "core").get
        )
        val ctx      = MonorepoContext(
          state = state,
          projects = current,
          interactive = false
        ).withReleasePlan(
          MonorepoSpecSupport.releasePlan(
            selectionMode = SelectionMode.ExplicitSelection,
            selectedNames = Seq("core")
          )
        )

        (repo, ctx, versionFile)
      }
    }
}
