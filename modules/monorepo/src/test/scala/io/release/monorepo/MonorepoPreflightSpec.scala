package io.release.monorepo

import cats.effect.IO
import cats.effect.Resource
import io.release.ReleaseIO.*
import io.release.TestAssertions.assertFailure
import io.release.TestSupport
import io.release.internal.HelpDocsLinks
import io.release.internal.SbtRuntime
import io.release.monorepo.steps.MonorepoReleaseSteps
import io.release.monorepo.steps.MonorepoStepTestCompat
import io.release.monorepo.steps.MonorepoVcsSteps
import munit.CatsEffectSuite

import java.io.File

@scala.annotation.nowarn("cat=deprecation")
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
    assert(lines.exists(_.contains("default-tag-exists-answer <o|k|a|<tag-name>>")))
    assert(lines.exists(_.contains("default-snapshot-dependencies-answer <y|n>")))
    assert(lines.exists(_.contains("default-remote-check-failure-answer <y|n>")))
    assert(lines.exists(_.contains("default-upstream-behind-answer <y|n>")))
    assert(lines.exists(_.contains("default-push-answer <y|n>")))
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
                             MonorepoReleaseSteps.tagReleasesPerProject
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

  test("check - fail when release version task reports FailureCommand during version snapshot") {
    preflightFixtureResource.use { case (repo, ctx, _) =>
      val marker     = new File(repo, "preflight-release-version-task.marker")
      val projectRef = MonorepoSpecSupport.projectNamed(ctx.projects, "core").ref

      for {
        mutatedState <- IO.blocking {
                          SbtRuntime.appendWithSession(
                            ctx.state,
                            Seq(
                              MonorepoStepTestCompat.failureCommandVersionTaskSetting(
                                projectRef,
                                marker
                              )
                            )
                          )
                        }
        mutatedCtx    = ctx.withState(mutatedState)
        session       = MonorepoPreparedSession(
                          mutatedCtx.state,
                          mutatedCtx.releasePlan.get,
                          mutatedCtx
                        )
        _            <- assertFailure[IllegalStateException, MonorepoPreflight.Summary](
                          MonorepoPreflight.check(session, Seq(MonorepoReleaseSteps.inquireVersions))
                        ) { err =>
                          assert(marker.exists())
                          assert(err.getMessage.contains(releaseIOVersioningReleaseVersion.key.label))
                          assert(err.getMessage.contains("FailureCommand"))
                        }
      } yield ()
    }
  }

  test("check - fail setup validations before version resolution is attempted") {
    preflightFixtureResource.use { case (repo, ctx, _) =>
      val versionResolutionFailure = "version resolution should not run"

      for {
        _            <- IO.blocking(sbt.IO.write(new File(repo, "tracked.txt"), "dirty"))
        mutatedState <- IO.blocking {
                          SbtRuntime.appendWithSession(
                            ctx.state,
                            Seq(
                              MonorepoReleaseIO.releaseIOMonorepoVersioningReadVersion := {
                                (_: File) =>
                                  IO.raiseError(new IllegalStateException(versionResolutionFailure))
                              }
                            )
                          )
                        }
        dirtyCtx      = ctx.withState(mutatedState)
        session       = MonorepoPreparedSession(
                          dirtyCtx.state,
                          dirtyCtx.releasePlan.get,
                          dirtyCtx
                        )
        _            <- assertFailure[IllegalStateException, MonorepoPreflight.Summary](
                          MonorepoPreflight.check(
                            session,
                            Seq(
                              MonorepoReleaseSteps.checkCleanWorkingDir,
                              MonorepoReleaseSteps.detectOrSelectProjects,
                              MonorepoReleaseSteps.inquireVersions
                            )
                          )
                        ) { err =>
                          assert(err.getMessage.contains("unstaged modified files"))
                          assert(!err.getMessage.contains(versionResolutionFailure))
                        }
      } yield ()
    }
  }

  test("check - render summary from the fully validated context") {
    preflightFixtureResource.use { case (_, ctx, _) =>
      val session = MonorepoPreparedSession(ctx.state, ctx.releasePlan.get, ctx)

      MonorepoPreflight
        .check(
          session,
          Seq(
            MonorepoReleaseSteps.detectOrSelectProjects,
            skipPublishInValidationStep,
            MonorepoReleaseSteps.publishArtifacts
          )
        )
        .map { summary =>
          assertEquals(
            summary.selectionMode,
            MonorepoPreflight.Evaluation.Resolved(SelectionMode.ExplicitSelection)
          )
          assertEquals(summary.projects.map(_.name), Seq("core"))
          assertEquals(
            summary.publishSummary,
            "skipped via releaseIOMonorepoBehaviorSkipPublish := true"
          )
        }
    }
  }

  test("check - keep tag preflight and rendered projects aligned after validation updates") {
    multiProjectPreflightFixtureResource.use { case (_, ctx) =>
      val session = MonorepoPreparedSession(ctx.state, ctx.releasePlan.get, ctx)

      MonorepoPreflight
        .check(
          session,
          Seq(
            MonorepoReleaseSteps.detectOrSelectProjects,
            MonorepoReleaseSteps.inquireVersions,
            narrowProjectsInValidationStep("core"),
            MonorepoReleaseSteps.tagReleasesPerProject
          )
        )
        .map { summary =>
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

  test("check - bootstrap built-in initialize-vcs for custom no-boundary validations") {
    preflightFixtureResource.use { case (_, ctx, _) =>
      val session = MonorepoPreparedSession(ctx.state, ctx.releasePlan.get, ctx)

      MonorepoPreflight
        .check(
          session,
          Seq(MonorepoReleaseSteps.initializeVcs, requiresVcsValidationStep)
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

  test("check - fail no-boundary validations before built-in version resolution is attempted") {
    preflightFixtureResource.use { case (repo, ctx, _) =>
      val versionResolutionFailure = "version resolution should not run"

      for {
        _            <- IO.blocking(sbt.IO.write(new File(repo, "tracked.txt"), "dirty"))
        mutatedState <- IO.blocking {
                          SbtRuntime.appendWithSession(
                            ctx.state,
                            Seq(
                              MonorepoReleaseIO.releaseIOMonorepoVersioningReadVersion := {
                                (_: File) =>
                                  IO.raiseError(new IllegalStateException(versionResolutionFailure))
                              }
                            )
                          )
                        }
        dirtyCtx      = ctx.withState(mutatedState)
        session       = MonorepoPreparedSession(
                          dirtyCtx.state,
                          dirtyCtx.releasePlan.get,
                          dirtyCtx
                        )
        _            <- assertFailure[IllegalStateException, MonorepoPreflight.Summary](
                          MonorepoPreflight.check(
                            session,
                            Seq(
                              MonorepoReleaseSteps.checkCleanWorkingDir,
                              MonorepoReleaseSteps.inquireVersions
                            )
                          )
                        ) { err =>
                          assert(err.getMessage.contains("unstaged modified files"))
                          assert(!err.getMessage.contains(versionResolutionFailure))
                        }
      } yield ()
    }
  }

  test("check - validate no-boundary suffix steps after built-in version resolution") {
    preflightFixtureResource.use { case (_, ctx, _) =>
      val session = MonorepoPreparedSession(ctx.state, ctx.releasePlan.get, ctx)

      MonorepoPreflight
        .check(
          session,
          Seq(
            MonorepoReleaseSteps.inquireVersions,
            requiresResolvedVersionsValidationStep
          )
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
            Seq(
              MonorepoPreflight.Evaluation.Resolved(
                MonorepoPreflight.ProjectVersions("0.1.0", "0.2.0-SNAPSHOT")
              )
            )
          )
        }
    }
  }

  test(
    "check - keep no-boundary tag preflight and rendered projects aligned after validation updates"
  ) {
    multiProjectPreflightFixtureResource.use { case (_, ctx) =>
      val session = MonorepoPreparedSession(ctx.state, ctx.releasePlan.get, ctx)

      MonorepoPreflight
        .check(
          session,
          Seq(
            MonorepoReleaseSteps.inquireVersions,
            narrowProjectsInValidationStep("core"),
            MonorepoReleaseSteps.tagReleasesPerProject
          )
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
        }
    }
  }

  test(
    "check - keep no-boundary tags not evaluated when tag-releases appears before inquire-versions"
  ) {
    preflightFixtureResource.use { case (_, ctx, _) =>
      val session = MonorepoPreparedSession(ctx.state, ctx.releasePlan.get, ctx)

      MonorepoPreflight
        .check(
          session,
          Seq(
            MonorepoReleaseSteps.tagReleasesPerProject,
            MonorepoReleaseSteps.inquireVersions
          )
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
            Seq(
              MonorepoPreflight.Evaluation.Resolved(
                MonorepoPreflight.ProjectVersions("0.1.0", "0.2.0-SNAPSHOT")
              )
            )
          )
          assertEquals(
            summary.projects.map(_.tag),
            Seq(
              MonorepoPreflight.Evaluation.NotEvaluated(
                "tags depend on runtime/custom version setup"
              )
            )
          )
        }
    }
  }

  test(
    "check - fail custom no-boundary validations that require VCS when initialize-vcs is absent"
  ) {
    preflightFixtureResource.use { case (_, ctx, _) =>
      val session = MonorepoPreparedSession(ctx.state, ctx.releasePlan.get, ctx)

      assertFailure[IllegalStateException, MonorepoPreflight.Summary](
        MonorepoPreflight.check(session, Seq(requiresVcsValidationStep))
      )(err => assert(err.getMessage.contains("expected preflight VCS context")))
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
      for {
        fixture             <- IO.blocking {
                                 val coreBase    = new File(repo, "core")
                                 coreBase.mkdirs()
                                 val versionFile = new File(coreBase, "version.sbt")
                                 sbt.IO.write(new File(repo, "tracked.txt"), "initial")
                                 sbt.IO.write(
                                   versionFile,
                                   """version := "0.1.0-SNAPSHOT"""" + "\n"
                                 )

                                 TestSupport.initGitRepo(repo)
                                 TestSupport.commitAll(repo, "Initial commit")

                                 val projects = Seq(
                                   MonorepoSpecSupport.monorepoRootProject(
                                     repo,
                                     projectIds = Seq("core"),
                                     settings = Seq(
                                       io.release.ReleaseIO.releaseIOVcsIgnoreUntrackedFiles := true
                                     )
                                   ),
                                   MonorepoSpecSupport.versionedProject(
                                     "core",
                                     coreBase,
                                     settings = Seq(
                                       io.release.ReleaseIO.releaseIOVersioningReleaseVersion :=
                                         ((version: String) => version.stripSuffix("-SNAPSHOT")),
                                       io.release.ReleaseIO.releaseIOVersioningNextVersion    :=
                                         ((_: String) => "0.2.0-SNAPSHOT")
                                     )
                                   )
                                 )
                                 val state    =
                                   TestSupport.loadedState(
                                     repo,
                                     projects,
                                     currentProjectId = Some("root")
                                   )

                                 (state, versionFile)
                               }
        (state, versionFile) = fixture
        resolved            <- MonorepoProjectResolver.resolveAll(state)
      } yield {
        val current = Seq(
          resolved.find(_.name == "core").getOrElse {
            fail("Expected resolved project 'core' in preflight fixture")
          }
        )
        val ctx     = MonorepoContext(
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

  private val requiresVcsValidationStep: MonorepoStepIO.Global =
    MonorepoStepIO
      .global("requires-vcs")
      .withValidation(ctx =>
        IO.raiseUnless(ctx.vcs.nonEmpty)(
          new IllegalStateException("expected preflight VCS context")
        )
      )
      .validateOnly

  private val skipPublishInValidationStep: MonorepoStepIO.Global =
    MonorepoStepIO
      .global("skip-publish-in-validation")
      .withValidationContext(currentCtx => IO.pure(currentCtx.copy(skipPublish = true)))
      .validateOnly

  private val requiresResolvedVersionsValidationStep: MonorepoStepIO.Global =
    MonorepoStepIO
      .global("requires-resolved-versions")
      .withValidation(currentCtx =>
        IO.raiseUnless(
          currentCtx.currentProjects.forall(_.versions.exists {
            case (releaseVersion, nextVersion) =>
              releaseVersion.nonEmpty && nextVersion.nonEmpty
          })
        )(
          new IllegalStateException("expected resolved versions during no-boundary validation")
        )
      )
      .validateOnly

  private def narrowProjectsInValidationStep(name: String): MonorepoStepIO.Global =
    MonorepoStepIO
      .global(s"narrow-projects-to-$name")
      .withValidationContext(currentCtx =>
        IO.pure(currentCtx.withProjects(currentCtx.currentProjects.filter(_.name == name)))
      )
      .validateOnly

  private val multiProjectPreflightFixtureResource: Resource[IO, (File, MonorepoContext)] =
    TestSupport.tempDirResource("monorepo-preflight-multi-spec").evalMap { repo =>
      for {
        state    <- IO.blocking {
                      val coreBase = new File(repo, "core")
                      val apiBase  = new File(repo, "api")
                      coreBase.mkdirs()
                      apiBase.mkdirs()

                      sbt.IO.write(new File(repo, "tracked.txt"), "initial")
                      sbt.IO.write(
                        new File(coreBase, "version.sbt"),
                        """version := "0.1.0-SNAPSHOT"""" + "\n"
                      )
                      sbt.IO.write(
                        new File(apiBase, "version.sbt"),
                        """version := "0.2.0-SNAPSHOT"""" + "\n"
                      )

                      TestSupport.initGitRepo(repo)
                      TestSupport.commitAll(repo, "Initial commit")

                      val versionSettings = Seq(
                        io.release.ReleaseIO.releaseIOVersioningReleaseVersion :=
                          ((version: String) => version.stripSuffix("-SNAPSHOT")),
                        io.release.ReleaseIO.releaseIOVersioningNextVersion    :=
                          ((_: String) => "0.2.0-SNAPSHOT")
                      )
                      val projects        = Seq(
                        MonorepoSpecSupport.monorepoRootProject(
                          repo,
                          projectIds = Seq("core", "api"),
                          settings = Seq(io.release.ReleaseIO.releaseIOVcsIgnoreUntrackedFiles := true)
                        ),
                        MonorepoSpecSupport.versionedProject(
                          "core",
                          coreBase,
                          settings = versionSettings
                        ),
                        MonorepoSpecSupport.versionedProject(
                          "api",
                          apiBase,
                          settings = versionSettings
                        )
                      )
                      TestSupport.loadedState(repo, projects, currentProjectId = Some("root"))
                    }
        resolved <- MonorepoProjectResolver.resolveAll(state)
      } yield {
        val current = resolved.filter(project => Set("core", "api").contains(project.name))
        val ctx     = MonorepoContext(
          state = state,
          projects = current,
          interactive = false
        ).withReleasePlan(
          MonorepoSpecSupport.releasePlan(
            selectionMode = SelectionMode.ExplicitSelection,
            selectedNames = Seq("core", "api")
          )
        )

        (repo, ctx)
      }
    }
}
