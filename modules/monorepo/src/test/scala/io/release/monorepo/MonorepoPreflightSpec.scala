package io.release.monorepo

import cats.effect.IO
import cats.effect.Ref
import cats.effect.Resource
import io.release.ReleaseManifestMetadataSupport
import io.release.ReleaseSharedKeys.*
import io.release.TestAssertions.assertFailure
import io.release.TestSupport
import io.release.monorepo.internal.*
import io.release.monorepo.internal.MonorepoStepAliases.GlobalStep
import io.release.monorepo.internal.MonorepoStepAliases.ProjectStep
import io.release.monorepo.internal.steps.MonorepoReleaseSteps
import io.release.monorepo.internal.steps.MonorepoStepTestCompat
import io.release.monorepo.internal.steps.MonorepoVcsSteps
import io.release.runtime.command.HelpDocsLinks
import io.release.runtime.engine.ProcessStep
import io.release.runtime.sbt.SbtRuntime
import munit.CatsEffectSuite

import java.io.File

class MonorepoPreflightSpec extends CatsEffectSuite with MonorepoDummyProjectSupport {

  test("renderSummary - include selection and per-project tag summaries") {
    val summary = MonorepoPreflight.Summary(
      selectionMode = MonorepoPreflight.Evaluation.Resolved(SelectionMode.ExplicitSelection),
      projects = MonorepoPreflight.Evaluation.Resolved(
        Seq(
          MonorepoPreflight.ProjectSummary(
            name = "core",
            versions = MonorepoPreflight.Evaluation.Resolved(
              MonorepoPreflight.ProjectVersions("1.0.0", "1.1.0-SNAPSHOT")
            ),
            tag = MonorepoPreflight.Evaluation.Resolved(
              MonorepoPreflight.ProjectTag("core/v1.0.0", "available")
            )
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

  test("renderSummary - show deferred projects as a single not-evaluated line") {
    val summary = MonorepoPreflight.Summary(
      selectionMode = MonorepoPreflight.Evaluation.NotEvaluated(
        "selection depends on runtime hook state"
      ),
      projects = MonorepoPreflight.Evaluation.NotEvaluated(
        "projects depend on runtime hook state"
      ),
      crossBuildEnabled = false,
      publishSummary = "step not configured",
      pushSummary = "step not configured",
      stepNames = Seq("after-clean-check:late-bound-selection", "detect-or-select-projects")
    )

    val lines = MonorepoPreflight.renderSummary(summary)

    assert(
      lines.contains("  projects      : not evaluated (projects depend on runtime hook state)")
    )
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
    assert(
      lines.exists(
        _.contains(
          "Selection, projects, versions, and tags are summarized only when runtime hook state"
        )
      )
    )
    assert(lines.exists(_.contains("Otherwise the preflight reports them as not evaluated")))
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

  test(
    "check - fail on shared version-file config during built-in write validation without mutating files or tags"
  ) {
    TestSupport.tempDirResource("monorepo-preflight-shared-version-file").use { repo =>
      for {
        fixture                                                    <- IO.blocking {
                                                                        val coreBase          = new File(repo, "core")
                                                                        val apiBase           = new File(repo, "api")
                                                                        val sharedVersionFile = new File(repo, "version.sbt")
                                                                        val coreVersionFile   = new File(coreBase, "version.sbt")
                                                                        val apiVersionFile    = new File(apiBase, "version.sbt")
                                                                        coreBase.mkdirs()
                                                                        apiBase.mkdirs()
                                                                        sbt.IO.write(new File(repo, "tracked.txt"), "initial")
                                                                        sbt.IO.write(
                                                                          sharedVersionFile,
                                                                          """version := "0.1.0-SNAPSHOT"""" + "\n"
                                                                        )
                                                                        sbt.IO.write(
                                                                          coreVersionFile,
                                                                          """version := "0.1.0-SNAPSHOT"""" + "\n"
                                                                        )
                                                                        sbt.IO.write(
                                                                          apiVersionFile,
                                                                          """version := "0.1.0-SNAPSHOT"""" + "\n"
                                                                        )

                                                                        TestSupport.initGitRepo(repo)
                                                                        TestSupport.commitAll(repo, "Initial commit")

                                                                        val projectSettings = Seq(
                                                                          releaseIOVersioningReleaseVersion := ((version: String) =>
                                                                            version.stripSuffix("-SNAPSHOT")
                                                                          ),
                                                                          releaseIOVersioningNextVersion    := ((_: String) => "0.2.0-SNAPSHOT")
                                                                        )
                                                                        val projects        = Seq(
                                                                          MonorepoSpecSupport.monorepoRootProject(
                                                                            repo,
                                                                            projectIds = Seq("core", "api"),
                                                                            settings = Seq(
                                                                              releaseIOVcsIgnoreUntrackedFiles                                 := true,
                                                                              MonorepoReleasePlugin.autoImport.releaseIOMonorepoVersioningFile := {
                                                                                (_: sbt.ProjectRef, _: sbt.State) =>
                                                                                  sharedVersionFile
                                                                              }
                                                                            )
                                                                          ),
                                                                          MonorepoSpecSupport.versionedProject(
                                                                            "core",
                                                                            coreBase,
                                                                            settings = projectSettings
                                                                          ),
                                                                          MonorepoSpecSupport.versionedProject(
                                                                            "api",
                                                                            apiBase,
                                                                            settings = projectSettings
                                                                          )
                                                                        )
                                                                        val state           =
                                                                          TestSupport.loadedState(
                                                                            repo,
                                                                            projects,
                                                                            currentProjectId = Some("root")
                                                                          )

                                                                        (
                                                                          state,
                                                                          sharedVersionFile,
                                                                          coreVersionFile,
                                                                          apiVersionFile
                                                                        )
                                                                      }
        (state, sharedVersionFile, coreVersionFile, apiVersionFile) = fixture
        resolved                                                   <- MonorepoProjectResolver.resolveAll(state)
        current                                                     = Seq(
                                                                        resolved.find(_.name == "core").getOrElse {
                                                                          fail(
                                                                            "Expected resolved project 'core' in shared-version-file fixture"
                                                                          )
                                                                        }
                                                                      )
        ctx                                                         = MonorepoContext(
                                                                        state = state,
                                                                        projects = current,
                                                                        interactive = false
                                                                      ).withReleasePlan(
                                                                        MonorepoSpecSupport.releasePlan(
                                                                          selectionMode = SelectionMode.ExplicitSelection,
                                                                          selectedNames = Seq("core")
                                                                        )
                                                                      )
        session                                                     = MonorepoPreparedSession(
                                                                        ctx.state,
                                                                        ctx.releasePlan.get,
                                                                        ctx
                                                                      )
        beforeShared                                               <- IO.blocking(sbt.IO.read(sharedVersionFile))
        beforeCore                                                 <- IO.blocking(sbt.IO.read(coreVersionFile))
        beforeApi                                                  <- IO.blocking(sbt.IO.read(apiVersionFile))
        beforeTags                                                 <- IO.blocking(TestSupport.runGit(repo, "tag", "--list"))
        _                                                          <- assertFailure[IllegalStateException, MonorepoPreflight.Summary](
                                                                        MonorepoPreflight.check(
                                                                          session,
                                                                          Seq(
                                                                            MonorepoReleaseSteps.detectOrSelectProjects,
                                                                            MonorepoReleaseSteps.inquireVersions,
                                                                            MonorepoReleaseSteps.setReleaseVersions
                                                                          )
                                                                        )
                                                                      ) { err =>
                                                                        assert(
                                                                          err.getMessage.contains(
                                                                            "Multiple projects resolve to the same version file"
                                                                          )
                                                                        )
                                                                        assert(err.getMessage.contains("core"))
                                                                        assert(err.getMessage.contains("api"))
                                                                      }
        afterShared                                                <- IO.blocking(sbt.IO.read(sharedVersionFile))
        afterCore                                                  <- IO.blocking(sbt.IO.read(coreVersionFile))
        afterApi                                                   <- IO.blocking(sbt.IO.read(apiVersionFile))
        afterTags                                                  <- IO.blocking(TestSupport.runGit(repo, "tag", "--list"))
      } yield {
        assertEquals(beforeShared, afterShared)
        assertEquals(beforeCore, afterCore)
        assertEquals(beforeApi, afterApi)
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
                              MonorepoReleasePlugin.autoImport.releaseIOMonorepoVersioningReadVersion := {
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

  test("check - fail fast when validation returns ctx.failWith") {
    preflightFixtureResource.use { case (_, ctx, _) =>
      val session     = MonorepoPreparedSession(ctx.state, ctx.releasePlan.get, ctx)
      val failingStep =
        validationOnlyStep(
          "validation-fail-with",
          validateWithContext =
            currentCtx => IO.pure(currentCtx.failWith(new RuntimeException("fatal stop")))
        )

      assertFailure[RuntimeException, MonorepoPreflight.Summary](
        MonorepoPreflight.check(session, Seq(failingStep))
      )(err => assert(err.getMessage.contains("fatal stop")))
    }
  }

  test("check - fail main-segment validations before built-in version resolution after selection") {
    preflightFixtureResource.use { case (_, ctx, _) =>
      val versionResolutionFailure = "version resolution should not run"
      val validationFailure        = "after-selection validation failed"
      val failAfterSelectionStep   =
        validationOnlyStep(
          "fail-after-selection",
          validate = _ => IO.raiseError(new IllegalStateException(validationFailure))
        )

      for {
        mutatedState <- IO.blocking {
                          SbtRuntime.appendWithSession(
                            ctx.state,
                            Seq(
                              MonorepoReleasePlugin.autoImport.releaseIOMonorepoVersioningReadVersion := {
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
                              MonorepoReleaseSteps.detectOrSelectProjects,
                              failAfterSelectionStep,
                              MonorepoReleaseSteps.inquireVersions
                            )
                          )
                        ) { err =>
                          assert(err.getMessage.contains(validationFailure))
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
    "check - defer selection and project summaries when after-clean-check hooks can change selection"
  ) {
    preflightFixtureResource.use { case (_, ctx, _) =>
      val session = MonorepoPreparedSession(ctx.state, ctx.releasePlan.get, ctx)

      MonorepoPreflight
        .check(
          session,
          Seq(
            MonorepoReleaseSteps.checkCleanWorkingDir,
            validationOnlyStep("after-clean-check:late-bound-selection-settings"),
            MonorepoReleaseSteps.detectOrSelectProjects,
            MonorepoReleaseSteps.inquireVersions,
            MonorepoReleaseSteps.tagReleasesPerProject
          )
        )
        .map { summary =>
          assertEquals(
            summary.selectionMode,
            MonorepoPreflight.Evaluation.NotEvaluated(
              "selection depends on runtime hook state"
            )
          )
          assertProjectsNotEvaluated(summary, "projects depend on runtime hook state")
          assert(
            MonorepoPreflight
              .renderSummary(summary)
              .contains(
                "  projects      : not evaluated (projects depend on runtime hook state)"
              )
          )
          assert(summary.stepNames.contains("after-clean-check:late-bound-selection-settings"))
        }
    }
  }

  test(
    "check - defer selection and project summaries when before-selection hooks can change selection"
  ) {
    preflightFixtureResource.use { case (_, ctx, _) =>
      val session = MonorepoPreparedSession(ctx.state, ctx.releasePlan.get, ctx)

      MonorepoPreflight
        .check(
          session,
          Seq(
            validationOnlyStep("before-selection:late-bound-selection-settings"),
            MonorepoReleaseSteps.detectOrSelectProjects,
            MonorepoReleaseSteps.inquireVersions,
            MonorepoReleaseSteps.tagReleasesPerProject
          )
        )
        .map { summary =>
          assertEquals(
            summary.selectionMode,
            MonorepoPreflight.Evaluation.NotEvaluated(
              "selection depends on runtime hook state"
            )
          )
          assertProjectsNotEvaluated(summary, "projects depend on runtime hook state")
          assert(summary.stepNames.contains("before-selection:late-bound-selection-settings"))
        }
    }
  }

  test(
    "check - run after-selection hook validation on the selected context and defer project summaries"
  ) {
    multiProjectPreflightFixtureResource.use { case (_, ctx) =>
      val selectedPlan = MonorepoSpecSupport.releasePlan(
        selectionMode = SelectionMode.ExplicitSelection,
        selectedNames = Seq("api")
      )
      val selectedCtx  = ctx.withReleasePlan(selectedPlan)
      val session      = MonorepoPreparedSession(selectedCtx.state, selectedPlan, selectedCtx)
      val afterSelect  =
        validationOnlyStep(
          "after-selection:observe-selected-projects",
          validate =
            currentCtx => IO(assertEquals(currentCtx.currentProjects.map(_.name), Seq("api")))
        )

      MonorepoPreflight
        .check(
          session,
          Seq(
            MonorepoReleaseSteps.detectOrSelectProjects,
            afterSelect,
            MonorepoReleaseSteps.inquireVersions,
            MonorepoReleaseSteps.tagReleasesPerProject
          )
        )
        .map { summary =>
          assertEquals(
            summary.selectionMode,
            MonorepoPreflight.Evaluation.Resolved(SelectionMode.ExplicitSelection)
          )
          assertProjectsNotEvaluated(summary, "projects depend on runtime hook state")
          assert(summary.stepNames.contains("after-selection:observe-selected-projects"))
        }
    }
  }

  test(
    "check - validate only the safe after-selection hook prefix when later hooks depend on execute state"
  ) {
    multiProjectPreflightFixtureResource.use { case (_, ctx) =>
      Ref.of[IO, List[String]](Nil).flatMap { observed =>
        val selectedPlan = MonorepoSpecSupport.releasePlan(
          selectionMode = SelectionMode.ExplicitSelection,
          selectedNames = Seq("api")
        )
        val selectedCtx  = ctx.withReleasePlan(selectedPlan)
        val session      = MonorepoPreparedSession(selectedCtx.state, selectedPlan, selectedCtx)
        val firstHook    = ProcessStep.Single[MonorepoContext](
          name = "after-selection:retarget-projects",
          validate = (currentCtx: MonorepoContext) =>
            observed
              .update(
                _ :+ s"validate-first:${currentCtx.currentProjects.map(_.name).mkString(",")}"
              )
              .void,
          execute = (currentCtx: MonorepoContext) =>
            observed
              .update(_ :+ "execute-first")
              .as(currentCtx.withProjects(currentCtx.currentProjects.filter(_.name == "core")))
        )
        val secondHook   = ProcessStep.Single[MonorepoContext](
          name = "after-selection:requires-retargeted-projects",
          validate = (currentCtx: MonorepoContext) =>
            if (currentCtx.currentProjects.map(_.name) == Seq("core"))
              observed
                .update(
                  _ :+ s"validate-second:${currentCtx.currentProjects.map(_.name).mkString(",")}"
                )
                .void
            else
              IO.raiseError(
                new IllegalStateException(
                  s"expected retargeted projects, got ${currentCtx.currentProjects.map(_.name).mkString(",")}"
                )
              ),
          execute = (currentCtx: MonorepoContext) =>
            observed
              .update(_ :+ "execute-second")
              .as(currentCtx)
        )

        MonorepoPreflight
          .check(
            session,
            Seq(
              MonorepoReleaseSteps.detectOrSelectProjects,
              firstHook,
              secondHook,
              MonorepoReleaseSteps.inquireVersions,
              MonorepoReleaseSteps.tagReleasesPerProject
            )
          )
          .flatMap { summary =>
            observed.get.map { logged =>
              assertEquals(
                summary.selectionMode,
                MonorepoPreflight.Evaluation.Resolved(SelectionMode.ExplicitSelection)
              )
              assertProjectsNotEvaluated(summary, "projects depend on runtime hook state")
              assertEquals(logged, List("validate-first:api"))
            }
          }
      }
    }
  }

  test("check - skip main validation when before-selection hooks defer project selection") {
    multiProjectPreflightFixtureResource.use { case (_, ctx) =>
      val selectedPlan = MonorepoSpecSupport.releasePlan(
        selectionMode = SelectionMode.ExplicitSelection,
        selectedNames = Seq("api")
      )
      val selectedCtx  = ctx.withReleasePlan(selectedPlan)
      val session      = MonorepoPreparedSession(selectedCtx.state, selectedPlan, selectedCtx)

      MonorepoPreflight
        .check(
          session,
          Seq(
            executeOnlyStep(
              "before-selection:retarget-selection",
              execute = currentCtx =>
                IO.pure(
                  currentCtx.withReleasePlan(
                    selectedPlan.copy(selectedNames = Seq("core"))
                  )
                )
            ),
            MonorepoReleaseSteps.detectOrSelectProjects,
            failOnProjectValidationStep(
              projectName = "api",
              message = "stale prepared-session project should not be validated"
            ),
            MonorepoReleaseSteps.inquireVersions
          )
        )
        .map { summary =>
          assertEquals(
            summary.selectionMode,
            MonorepoPreflight.Evaluation.NotEvaluated(
              "selection depends on runtime hook state"
            )
          )
          assertProjectsNotEvaluated(summary, "projects depend on runtime hook state")
        }
    }
  }

  test("check - skip main validation when after-selection hooks can still rewrite projects") {
    multiProjectPreflightFixtureResource.use { case (_, ctx) =>
      val selectedPlan = MonorepoSpecSupport.releasePlan(
        selectionMode = SelectionMode.ExplicitSelection,
        selectedNames = Seq("api")
      )
      val selectedCtx  = ctx.withReleasePlan(selectedPlan)
      val session      = MonorepoPreparedSession(selectedCtx.state, selectedPlan, selectedCtx)

      MonorepoPreflight
        .check(
          session,
          Seq(
            MonorepoReleaseSteps.detectOrSelectProjects,
            executeOnlyStep(
              "after-selection:swap-projects",
              execute = currentCtx =>
                IO.pure(
                  currentCtx.withProjects(currentCtx.currentProjects.filter(_.name == "core"))
                )
            ),
            failOnProjectValidationStep(
              projectName = "api",
              message = "pre-hook selected project should not be validated"
            ),
            MonorepoReleaseSteps.inquireVersions
          )
        )
        .map { summary =>
          assertEquals(
            summary.selectionMode,
            MonorepoPreflight.Evaluation.Resolved(SelectionMode.ExplicitSelection)
          )
          assertProjectsNotEvaluated(summary, "projects depend on runtime hook state")
        }
    }
  }

  test(
    "check - defer versions and tags when after-version-resolution hooks can rewrite project versions"
  ) {
    preflightFixtureResource.use { case (_, ctx, _) =>
      val session = MonorepoPreparedSession(ctx.state, ctx.releasePlan.get, ctx)

      MonorepoPreflight
        .check(
          session,
          Seq(
            MonorepoReleaseSteps.detectOrSelectProjects,
            MonorepoReleaseSteps.inquireVersions,
            validationOnlyStep("after-version-resolution:rewrite-version-pair"),
            MonorepoReleaseSteps.tagReleasesPerProject
          )
        )
        .map { summary =>
          assertEquals(summary.projects.map(_.name), Seq("core"))
          assertEquals(
            summary.projects.map(_.versions),
            Seq(MonorepoPreflight.Evaluation.NotEvaluated("versions depend on runtime hook state"))
          )
          assertEquals(
            summary.projects.map(_.tag),
            Seq(MonorepoPreflight.Evaluation.NotEvaluated("tags depend on runtime hook state"))
          )
          assert(summary.stepNames.contains("after-version-resolution:rewrite-version-pair"))
        }
    }
  }

  test(
    "check - skip post-version validation when before-version-resolution hooks defer inquiry"
  ) {
    preflightFixtureResource.use { case (_, ctx, _) =>
      val session = MonorepoPreparedSession(ctx.state, ctx.releasePlan.get, ctx)

      MonorepoPreflight
        .check(
          session,
          Seq(
            MonorepoReleaseSteps.detectOrSelectProjects,
            executeOnlyStep(
              "before-version-resolution:late-bound-version-overrides",
              execute = currentCtx =>
                IO.pure(
                  currentCtx.withReleasePlan(
                    currentCtx.releasePlan
                      .getOrElse(
                        fail("Expected release plan in preflight context")
                      )
                      .copy(
                        releaseVersionOverrides = Map("core" -> "1.2.3"),
                        nextVersionOverrides = Map("core" -> "1.2.4-SNAPSHOT")
                      )
                  )
                )
            ),
            MonorepoReleaseSteps.inquireVersions,
            requiresResolvedVersionsValidationStep,
            MonorepoReleaseSteps.tagReleasesPerProject
          )
        )
        .map { summary =>
          assertEquals(summary.projects.map(_.name), Seq("core"))
          assertEquals(
            summary.projects.map(_.versions),
            Seq(MonorepoPreflight.Evaluation.NotEvaluated("versions depend on runtime hook state"))
          )
          assertEquals(
            summary.projects.map(_.tag),
            Seq(MonorepoPreflight.Evaluation.NotEvaluated("tags depend on runtime hook state"))
          )
        }
    }
  }

  test(
    "check - keep post-version validation when versions were resolved before later hooks"
  ) {
    preflightFixtureResource.use { case (_, ctx, _) =>
      val session = MonorepoPreparedSession(ctx.state, ctx.releasePlan.get, ctx)

      MonorepoPreflight
        .check(
          session,
          Seq(
            MonorepoReleaseSteps.detectOrSelectProjects,
            MonorepoReleaseSteps.inquireVersions,
            validationOnlyStep("after-version-resolution:rewrite-version-pair"),
            requiresResolvedVersionsValidationStep,
            skipPublishInValidationStep,
            MonorepoReleaseSteps.publishArtifacts,
            MonorepoReleaseSteps.tagReleasesPerProject
          )
        )
        .map { summary =>
          assertEquals(summary.projects.map(_.name), Seq("core"))
          assertEquals(
            summary.projects.map(_.versions),
            Seq(MonorepoPreflight.Evaluation.NotEvaluated("versions depend on runtime hook state"))
          )
          assertEquals(
            summary.publishSummary,
            "skipped via releaseIOMonorepoBehaviorSkipPublish := true"
          )
          assertEquals(
            summary.projects.map(_.tag),
            Seq(MonorepoPreflight.Evaluation.NotEvaluated("tags depend on runtime hook state"))
          )
        }
    }
  }

  test("check - defer versions and tags when before-tag hooks can change tag inputs") {
    preflightFixtureResource.use { case (_, ctx, _) =>
      val session = MonorepoPreparedSession(ctx.state, ctx.releasePlan.get, ctx)

      MonorepoPreflight
        .check(
          session,
          Seq(
            MonorepoReleaseSteps.detectOrSelectProjects,
            MonorepoReleaseSteps.inquireVersions,
            validationOnlyStep("before-tag:before-tag-marker"),
            MonorepoReleaseSteps.tagReleasesPerProject
          )
        )
        .map { summary =>
          assertEquals(summary.projects.map(_.name), Seq("core"))
          assertEquals(
            summary.projects.map(_.versions),
            Seq(MonorepoPreflight.Evaluation.NotEvaluated("versions depend on runtime hook state"))
          )
          assertEquals(
            summary.projects.map(_.tag),
            Seq(MonorepoPreflight.Evaluation.NotEvaluated("tags depend on runtime hook state"))
          )
          assert(summary.stepNames.contains("before-tag:before-tag-marker"))
        }
    }
  }

  test("check - reject keep when the flow will create release commits before tagging") {
    preflightFixtureResource.use { case (repo, ctx, _) =>
      val keepCtx = withTagConflictDefaults(ctx, interactive = false, defaultAnswer = Some("k"))
      val session = MonorepoPreparedSession(keepCtx.state, keepCtx.releasePlan.get, keepCtx)

      IO.blocking(TestSupport.runGit(repo, "tag", "core/v0.1.0")) *>
        assertFailure[IllegalStateException, MonorepoPreflight.Summary](
          MonorepoPreflight.check(
            session,
            Seq(
              MonorepoReleaseSteps.detectOrSelectProjects,
              MonorepoReleaseSteps.inquireVersions,
              MonorepoReleaseSteps.setReleaseVersions,
              MonorepoReleaseSteps.commitReleaseVersions,
              MonorepoReleaseSteps.tagReleasesPerProject
            )
          )
        ) { err =>
          assert(
            err.getMessage.contains(
              "This release will create a new commit before tagging, so keeping the existing tag is not valid."
            )
          )
          assert(err.getMessage.contains("releaseIOMonorepo help"))
        }
    }
  }

  test("check - omit keep from the interactive tag summary for future release commits") {
    preflightFixtureResource.use { case (repo, ctx, _) =>
      val interactiveCtx =
        withTagConflictDefaults(ctx, interactive = true, defaultAnswer = None)
      val session        =
        MonorepoPreparedSession(
          interactiveCtx.state,
          interactiveCtx.releasePlan.get,
          interactiveCtx
        )

      IO.blocking(TestSupport.runGit(repo, "tag", "core/v0.1.0")) *>
        MonorepoPreflight
          .check(
            session,
            Seq(
              MonorepoReleaseSteps.detectOrSelectProjects,
              MonorepoReleaseSteps.inquireVersions,
              MonorepoReleaseSteps.setReleaseVersions,
              MonorepoReleaseSteps.commitReleaseVersions,
              MonorepoReleaseSteps.tagReleasesPerProject
            )
          )
          .map { summary =>
            assertEquals(
              summary.projects.map(_.tag),
              Seq(
                MonorepoPreflight.Evaluation.Resolved(
                  MonorepoPreflight.ProjectTag(
                    "core/v0.1.0",
                    "exists; release will create a new commit before tagging, so interactive release will prompt for overwrite, abort, or a new tag"
                  )
                )
              )
            )
          }
    }
  }

  test(
    "check - ignore seeded project release hashes when a future release commit will rewrite them"
  ) {
    preflightFixtureResource.use { case (repo, ctx, _) =>
      val keepCtx                       =
        withTagConflictDefaults(ctx, interactive = false, defaultAnswer = Some("k"))
      val seedProjectHashesInValidation =
        validationOnlyStep(
          "seed-project-release-hashes-in-validation",
          validateWithContext = currentCtx =>
            IO.blocking(TestSupport.runGit(repo, "rev-parse", "HEAD").trim).flatMap { headRev =>
              IO.blocking {
                val seededState = TestSupport.appendSessionSettings(
                  currentCtx.state,
                  ReleaseManifestMetadataSupport.releaseManifestHashSettings(
                    currentCtx.currentProjects.map(_.ref),
                    headRev
                  )
                )
                currentCtx.withState(seededState)
              }
            }
        )
      val session                       =
        MonorepoPreparedSession(keepCtx.state, keepCtx.releasePlan.get, keepCtx)

      IO.blocking(TestSupport.runGit(repo, "tag", "core/v0.1.0")) *>
        assertFailure[IllegalStateException, MonorepoPreflight.Summary](
          MonorepoPreflight.check(
            session,
            Seq(
              MonorepoReleaseSteps.detectOrSelectProjects,
              MonorepoReleaseSteps.inquireVersions,
              seedProjectHashesInValidation,
              MonorepoReleaseSteps.setReleaseVersions,
              MonorepoReleaseSteps.commitReleaseVersions,
              MonorepoReleaseSteps.tagReleasesPerProject
            )
          )
        ) { err =>
          assert(
            err.getMessage.contains(
              "This release will create a new commit before tagging, so keeping the existing tag is not valid."
            )
          )
          assert(err.getMessage.contains("releaseIOMonorepo help"))
        }
    }
  }

  test("check - keep the configured keep answer when no built-in release write precedes tags") {
    preflightFixtureResource.use { case (repo, ctx, _) =>
      val keepCtx = withTagConflictDefaults(ctx, interactive = false, defaultAnswer = Some("k"))
      val session = MonorepoPreparedSession(keepCtx.state, keepCtx.releasePlan.get, keepCtx)

      IO.blocking(TestSupport.runGit(repo, "tag", "core/v0.1.0")) *>
        MonorepoPreflight
          .check(
            session,
            Seq(
              MonorepoReleaseSteps.detectOrSelectProjects,
              MonorepoReleaseSteps.inquireVersions,
              MonorepoReleaseSteps.commitReleaseVersions,
              MonorepoReleaseSteps.tagReleasesPerProject
            )
          )
          .map { summary =>
            assertEquals(
              summary.projects.map(_.tag),
              Seq(
                MonorepoPreflight.Evaluation.Resolved(
                  MonorepoPreflight.ProjectTag(
                    "core/v0.1.0",
                    "exists; release will keep the existing tag"
                  )
                )
              )
            )
          }
    }
  }

  test("check - ignore a custom step that reuses the built-in release-write name") {
    preflightFixtureResource.use { case (repo, ctx, _) =>
      val keepCtx                          = withTagConflictDefaults(ctx, interactive = false, defaultAnswer = Some("k"))
      val session                          = MonorepoPreparedSession(keepCtx.state, keepCtx.releasePlan.get, keepCtx)
      val customSetReleaseVersionsNameStep =
        validationOnlyStep(MonorepoReleaseSteps.setReleaseVersions.name)

      IO.blocking(TestSupport.runGit(repo, "tag", "core/v0.1.0")) *>
        MonorepoPreflight
          .check(
            session,
            Seq(
              MonorepoReleaseSteps.detectOrSelectProjects,
              MonorepoReleaseSteps.inquireVersions,
              customSetReleaseVersionsNameStep,
              MonorepoReleaseSteps.commitReleaseVersions,
              MonorepoReleaseSteps.tagReleasesPerProject
            )
          )
          .map { summary =>
            assertEquals(
              summary.projects.map(_.tag),
              Seq(
                MonorepoPreflight.Evaluation.Resolved(
                  MonorepoPreflight.ProjectTag(
                    "core/v0.1.0",
                    "exists; release will keep the existing tag"
                  )
                )
              )
            )
          }
    }
  }

  test("check - keep keep in the interactive tag summary without a built-in release write") {
    preflightFixtureResource.use { case (repo, ctx, _) =>
      val interactiveCtx =
        withTagConflictDefaults(ctx, interactive = true, defaultAnswer = None)
      val session        =
        MonorepoPreparedSession(
          interactiveCtx.state,
          interactiveCtx.releasePlan.get,
          interactiveCtx
        )

      IO.blocking(TestSupport.runGit(repo, "tag", "core/v0.1.0")) *>
        MonorepoPreflight
          .check(
            session,
            Seq(
              MonorepoReleaseSteps.detectOrSelectProjects,
              MonorepoReleaseSteps.inquireVersions,
              MonorepoReleaseSteps.commitReleaseVersions,
              MonorepoReleaseSteps.tagReleasesPerProject
            )
          )
          .map { summary =>
            assertEquals(
              summary.projects.map(_.tag),
              Seq(
                MonorepoPreflight.Evaluation.Resolved(
                  MonorepoPreflight.ProjectTag(
                    "core/v0.1.0",
                    "exists; interactive release will prompt for overwrite, keep, abort, or a new tag"
                  )
                )
              )
            )
          }
    }
  }

  test("check - keep keep in the interactive summary for a custom step named set-release-version") {
    preflightFixtureResource.use { case (repo, ctx, _) =>
      val interactiveCtx                   =
        withTagConflictDefaults(ctx, interactive = true, defaultAnswer = None)
      val session                          =
        MonorepoPreparedSession(
          interactiveCtx.state,
          interactiveCtx.releasePlan.get,
          interactiveCtx
        )
      val customSetReleaseVersionsNameStep =
        validationOnlyStep(MonorepoReleaseSteps.setReleaseVersions.name)

      IO.blocking(TestSupport.runGit(repo, "tag", "core/v0.1.0")) *>
        MonorepoPreflight
          .check(
            session,
            Seq(
              MonorepoReleaseSteps.detectOrSelectProjects,
              MonorepoReleaseSteps.inquireVersions,
              customSetReleaseVersionsNameStep,
              MonorepoReleaseSteps.commitReleaseVersions,
              MonorepoReleaseSteps.tagReleasesPerProject
            )
          )
          .map { summary =>
            assertEquals(
              summary.projects.map(_.tag),
              Seq(
                MonorepoPreflight.Evaluation.Resolved(
                  MonorepoPreflight.ProjectTag(
                    "core/v0.1.0",
                    "exists; interactive release will prompt for overwrite, keep, abort, or a new tag"
                  )
                )
              )
            )
          }
    }
  }

  test("check - keep the configured keep answer when the built-in release write is a no-op") {
    noOpPreflightFixtureResource.use { case (repo, ctx, _) =>
      val keepCtx = withTagConflictDefaults(ctx, interactive = false, defaultAnswer = Some("k"))
      val session = MonorepoPreparedSession(keepCtx.state, keepCtx.releasePlan.get, keepCtx)

      IO.blocking(TestSupport.runGit(repo, "tag", "core/v0.1.0")) *>
        MonorepoPreflight
          .check(
            session,
            Seq(
              MonorepoReleaseSteps.detectOrSelectProjects,
              MonorepoReleaseSteps.inquireVersions,
              MonorepoReleaseSteps.setReleaseVersions,
              MonorepoReleaseSteps.commitReleaseVersions,
              MonorepoReleaseSteps.tagReleasesPerProject
            )
          )
          .map { summary =>
            assertEquals(
              summary.projects.map(_.tag),
              Seq(
                MonorepoPreflight.Evaluation.Resolved(
                  MonorepoPreflight.ProjectTag(
                    "core/v0.1.0",
                    "exists; release will keep the existing tag"
                  )
                )
              )
            )
          }
    }
  }

  test("check - keep a tag on HEAD when the persisted project hash is stale") {
    noOpPreflightFixtureResource.use { case (repo, ctx, _) =>
      for {
        releaseCommitHash <- IO.blocking(TestSupport.runGit(repo, "rev-parse", "HEAD").trim)
        _                 <- IO.blocking {
                               sbt.IO.write(new File(repo, "tracked.txt"), "updated-after-hook")
                               TestSupport.commitAll(repo, "Post-commit hook commit")
                             }
        _                 <- IO.blocking(TestSupport.runGit(repo, "tag", "core/v0.1.0"))
        seededState        =
          TestSupport.appendSessionSettings(
            ctx.state,
            ReleaseManifestMetadataSupport.releaseManifestHashSettings(
              ctx.currentProjects.map(_.ref),
              releaseCommitHash
            )
          )
        keepCtx            = withTagConflictDefaults(
                               ctx.withState(seededState),
                               interactive = false,
                               defaultAnswer = Some("k")
                             )
        session            = MonorepoPreparedSession(
                               keepCtx.state,
                               keepCtx.releasePlan.get,
                               keepCtx
                             )
        summary           <- MonorepoPreflight.check(
                               session,
                               Seq(
                                 MonorepoReleaseSteps.detectOrSelectProjects,
                                 MonorepoReleaseSteps.inquireVersions,
                                 MonorepoReleaseSteps.setReleaseVersions,
                                 MonorepoReleaseSteps.commitReleaseVersions,
                                 MonorepoReleaseSteps.tagReleasesPerProject
                               )
                             )
      } yield {
        assertEquals(
          summary.projects.map(_.tag),
          Seq(
            MonorepoPreflight.Evaluation.Resolved(
              MonorepoPreflight.ProjectTag(
                "core/v0.1.0",
                "exists; release will keep the existing tag"
              )
            )
          )
        )
      }
    }
  }

  test("check - keep keep in the interactive summary when the release write is a no-op") {
    noOpPreflightFixtureResource.use { case (repo, ctx, _) =>
      val interactiveCtx =
        withTagConflictDefaults(ctx, interactive = true, defaultAnswer = None)
      val session        =
        MonorepoPreparedSession(
          interactiveCtx.state,
          interactiveCtx.releasePlan.get,
          interactiveCtx
        )

      IO.blocking(TestSupport.runGit(repo, "tag", "core/v0.1.0")) *>
        MonorepoPreflight
          .check(
            session,
            Seq(
              MonorepoReleaseSteps.detectOrSelectProjects,
              MonorepoReleaseSteps.inquireVersions,
              MonorepoReleaseSteps.setReleaseVersions,
              MonorepoReleaseSteps.commitReleaseVersions,
              MonorepoReleaseSteps.tagReleasesPerProject
            )
          )
          .map { summary =>
            assertEquals(
              summary.projects.map(_.tag),
              Seq(
                MonorepoPreflight.Evaluation.Resolved(
                  MonorepoPreflight.ProjectTag(
                    "core/v0.1.0",
                    "exists; interactive release will prompt for overwrite, keep, abort, or a new tag"
                  )
                )
              )
            )
          }
    }
  }

  test("check - reject keep when a later built-in release chain creates release commits") {
    preflightFixtureResource.use { case (repo, ctx, _) =>
      val keepCtx = withTagConflictDefaults(ctx, interactive = false, defaultAnswer = Some("k"))
      val session = MonorepoPreparedSession(keepCtx.state, keepCtx.releasePlan.get, keepCtx)

      IO.blocking(TestSupport.runGit(repo, "tag", "core/v0.1.0")) *>
        assertFailure[IllegalStateException, MonorepoPreflight.Summary](
          MonorepoPreflight.check(
            session,
            Seq(
              MonorepoReleaseSteps.detectOrSelectProjects,
              MonorepoReleaseSteps.inquireVersions,
              MonorepoReleaseSteps.commitReleaseVersions,
              MonorepoReleaseSteps.setReleaseVersions,
              MonorepoReleaseSteps.commitReleaseVersions,
              MonorepoReleaseSteps.tagReleasesPerProject
            )
          )
        ) { err =>
          assert(
            err.getMessage.contains(
              "This release will create a new commit before tagging, so keeping the existing tag is not valid."
            )
          )
          assert(err.getMessage.contains("releaseIOMonorepo help"))
        }
    }
  }

  test("check - omit keep when a later built-in release chain creates release commits") {
    preflightFixtureResource.use { case (repo, ctx, _) =>
      val interactiveCtx =
        withTagConflictDefaults(ctx, interactive = true, defaultAnswer = None)
      val session        =
        MonorepoPreparedSession(
          interactiveCtx.state,
          interactiveCtx.releasePlan.get,
          interactiveCtx
        )

      IO.blocking(TestSupport.runGit(repo, "tag", "core/v0.1.0")) *>
        MonorepoPreflight
          .check(
            session,
            Seq(
              MonorepoReleaseSteps.detectOrSelectProjects,
              MonorepoReleaseSteps.inquireVersions,
              MonorepoReleaseSteps.commitReleaseVersions,
              MonorepoReleaseSteps.setReleaseVersions,
              MonorepoReleaseSteps.commitReleaseVersions,
              MonorepoReleaseSteps.tagReleasesPerProject
            )
          )
          .map { summary =>
            assertEquals(
              summary.projects.map(_.tag),
              Seq(
                MonorepoPreflight.Evaluation.Resolved(
                  MonorepoPreflight.ProjectTag(
                    "core/v0.1.0",
                    "exists; release will create a new commit before tagging, so interactive release will prompt for overwrite, abort, or a new tag"
                  )
                )
              )
            )
          }
    }
  }

  test("check - treat all project tags as future-commit tags when any release write changes") {
    mixedPreflightFixtureResource.use { case (repo, ctx) =>
      val interactiveCtx =
        withTagConflictDefaults(ctx, interactive = true, defaultAnswer = None)
      val session        =
        MonorepoPreparedSession(
          interactiveCtx.state,
          interactiveCtx.releasePlan.get,
          interactiveCtx
        )

      IO.blocking(TestSupport.runGit(repo, "tag", "core/v0.1.0")) *>
        IO.blocking(TestSupport.runGit(repo, "tag", "api/v0.2.0")) *>
        MonorepoPreflight
          .check(
            session,
            Seq(
              MonorepoReleaseSteps.detectOrSelectProjects,
              MonorepoReleaseSteps.inquireVersions,
              MonorepoReleaseSteps.setReleaseVersions,
              MonorepoReleaseSteps.commitReleaseVersions,
              MonorepoReleaseSteps.tagReleasesPerProject
            )
          )
          .map { summary =>
            assertEquals(
              summary.projects.map(_.tag),
              Seq(
                MonorepoPreflight.Evaluation.Resolved(
                  MonorepoPreflight.ProjectTag(
                    "core/v0.1.0",
                    "exists; release will create a new commit before tagging, so interactive release will prompt for overwrite, abort, or a new tag"
                  )
                ),
                MonorepoPreflight.Evaluation.Resolved(
                  MonorepoPreflight.ProjectTag(
                    "api/v0.2.0",
                    "exists; release will create a new commit before tagging, so interactive release will prompt for overwrite, abort, or a new tag"
                  )
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
                              MonorepoReleasePlugin.autoImport.releaseIOMonorepoVersioningReadVersion := {
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

  test("renderProjects - fail when tag outcomes do not align with projects") {
    dummyProjects("core", "api").flatMap { rawProjects =>
      val projects = Seq(
        rawProjects.head.copy(versions = Some("1.0.0" -> "1.1.0-SNAPSHOT")),
        rawProjects(1).copy(versions = Some("2.0.0" -> "2.1.0-SNAPSHOT"))
      )
      val tags     = MonorepoPreflight.Evaluation.Resolved(
        Seq(
          MonorepoVcsSteps.PreflightTagOutcome("core", "core/v1.0.0", "available"),
          MonorepoVcsSteps.PreflightTagOutcome("api", "api/v2.0.0", "available"),
          MonorepoVcsSteps.PreflightTagOutcome("extra", "extra/v3.0.0", "available")
        )
      )

      assertFailure[IllegalStateException, Seq[MonorepoPreflight.ProjectSummary]](
        MonorepoPreflight.renderProjects(
          projects,
          versions = MonorepoPreflight.Evaluation.Resolved(()),
          tagOutcomes = tags
        )
      ) { err =>
        assert(err.getMessage.contains("inconsistent project/tag outcomes"))
        assert(err.getMessage.contains("outcomes without projects=[extra]"))
      }
    }
  }

  private implicit final class ProjectSummaryEvaluationOps(
      private val projects: MonorepoPreflight.Evaluation[Seq[MonorepoPreflight.ProjectSummary]]
  ) {
    def map[A](f: MonorepoPreflight.ProjectSummary => A): Seq[A] =
      projects match {
        case MonorepoPreflight.Evaluation.Resolved(resolvedProjects) =>
          resolvedProjects.map(f)
        case MonorepoPreflight.Evaluation.NotEvaluated(reason)       =>
          fail(s"Expected projects to be resolved, but got not evaluated ($reason)")
      }
  }

  private def assertProjectsNotEvaluated(
      summary: MonorepoPreflight.Summary,
      reason: String
  ): Unit =
    assertEquals(
      summary.projects,
      MonorepoPreflight.Evaluation.NotEvaluated(reason)
    )

  private val preflightFixtureResource: Resource[IO, (File, MonorepoContext, File)] =
    singleProjectPreflightFixtureResource("0.1.0-SNAPSHOT")

  private val noOpPreflightFixtureResource: Resource[IO, (File, MonorepoContext, File)] =
    singleProjectPreflightFixtureResource("0.1.0")

  private def singleProjectPreflightFixtureResource(
      initialVersion: String
  ): Resource[IO, (File, MonorepoContext, File)] =
    TestSupport.tempDirResource("monorepo-preflight-spec").evalMap { repo =>
      for {
        fixture             <- IO.blocking {
                                 val coreBase    = new File(repo, "core")
                                 coreBase.mkdirs()
                                 val versionFile = new File(coreBase, "version.sbt")
                                 sbt.IO.write(new File(repo, "tracked.txt"), "initial")
                                 sbt.IO.write(
                                   versionFile,
                                   s"""version := "$initialVersion"""" + "\n"
                                 )

                                 TestSupport.initGitRepo(repo)
                                 TestSupport.commitAll(repo, "Initial commit")

                                 val projects = Seq(
                                   MonorepoSpecSupport.monorepoRootProject(
                                     repo,
                                     projectIds = Seq("core"),
                                     settings = Seq(
                                       io.release.ReleaseSharedKeys.releaseIOVcsIgnoreUntrackedFiles := true
                                     )
                                   ),
                                   MonorepoSpecSupport.versionedProject(
                                     "core",
                                     coreBase,
                                     settings = Seq(
                                       io.release.ReleaseSharedKeys.releaseIOVersioningReleaseVersion :=
                                         ((version: String) => version.stripSuffix("-SNAPSHOT")),
                                       io.release.ReleaseSharedKeys.releaseIOVersioningNextVersion    :=
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

  private val requiresVcsValidationStep: GlobalStep =
    validationOnlyStep(
      "requires-vcs",
      validate = ctx =>
        IO.raiseUnless(ctx.vcs.nonEmpty)(
          new IllegalStateException("expected preflight VCS context")
        )
    )

  private val skipPublishInValidationStep: GlobalStep =
    validationOnlyStep(
      "skip-publish-in-validation",
      validateWithContext = currentCtx => IO.pure(currentCtx.copy(skipPublish = true))
    )

  private val requiresResolvedVersionsValidationStep: GlobalStep =
    validationOnlyStep(
      "requires-resolved-versions",
      validate = currentCtx =>
        IO.raiseUnless(
          currentCtx.currentProjects.forall(_.versions.exists {
            case (releaseVersion, nextVersion) =>
              releaseVersion.nonEmpty && nextVersion.nonEmpty
          })
        )(
          new IllegalStateException("expected resolved versions during no-boundary validation")
        )
    )

  private def narrowProjectsInValidationStep(
      name: String
  ): GlobalStep =
    validationOnlyStep(
      s"narrow-projects-to-$name",
      validateWithContext = currentCtx =>
        IO.pure(currentCtx.withProjects(currentCtx.currentProjects.filter(_.name == name)))
    )

  private def validationOnlyStep(
      name: String,
      validate: MonorepoContext => IO[Unit] = _ => IO.unit,
      validateWithContext: MonorepoContext => IO[MonorepoContext] = currentCtx =>
        IO.pure(currentCtx)
  ): GlobalStep =
    ProcessStep.Single(
      name = name,
      execute = currentCtx => IO.pure(currentCtx),
      validate = validate,
      validateWithContext = Some(validateWithContext)
    )

  private def executeOnlyStep(
      name: String,
      execute: MonorepoContext => IO[MonorepoContext]
  ): GlobalStep =
    ProcessStep.Single(
      name = name,
      execute = execute
    )

  private def validationOnlyProjectStep(
      name: String,
      validate: (MonorepoContext, ProjectReleaseInfo) => IO[Unit] =
        (_: MonorepoContext, _: ProjectReleaseInfo) => IO.unit,
      validateWithContext: (MonorepoContext, ProjectReleaseInfo) => IO[MonorepoContext] =
        (currentCtx, _: ProjectReleaseInfo) => IO.pure(currentCtx)
  ): ProjectStep =
    ProcessStep.PerItem(
      name = name,
      execute = (currentCtx, _: ProjectReleaseInfo) => IO.pure(currentCtx),
      validate = validate,
      validateWithContext = Some(validateWithContext)
    )

  private def failOnProjectValidationStep(
      projectName: String,
      message: String
  ): ProjectStep =
    validationOnlyProjectStep(
      s"fail-on-$projectName-validation",
      validate = (_, project) =>
        if (project.name == projectName) IO.raiseError(new IllegalStateException(message))
        else IO.unit
    )

  private def withTagConflictDefaults(
      ctx: MonorepoContext,
      interactive: Boolean,
      defaultAnswer: Option[String]
  ): MonorepoContext =
    ctx.releasePlan.getOrElse(fail("Expected release plan")) match {
      case plan =>
        ctx
          .copy(interactive = interactive)
          .withReleasePlan(
            plan.copy(
              flags = plan.flags.copy(interactive = interactive),
              decisionDefaults = plan.decisionDefaults.copy(tagExistsAnswer = defaultAnswer)
            )
          )
    }

  private val multiProjectPreflightFixtureResource: Resource[IO, (File, MonorepoContext)] =
    buildMultiProjectPreflightFixtureResource(
      coreVersion = "0.1.0-SNAPSHOT",
      apiVersion = "0.2.0-SNAPSHOT"
    )

  private val mixedPreflightFixtureResource: Resource[IO, (File, MonorepoContext)] =
    buildMultiProjectPreflightFixtureResource(
      coreVersion = "0.1.0",
      apiVersion = "0.2.0-SNAPSHOT"
    )

  private def buildMultiProjectPreflightFixtureResource(
      coreVersion: String,
      apiVersion: String
  ): Resource[IO, (File, MonorepoContext)] =
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
                        s"""version := "$coreVersion"""" + "\n"
                      )
                      sbt.IO.write(
                        new File(apiBase, "version.sbt"),
                        s"""version := "$apiVersion"""" + "\n"
                      )

                      TestSupport.initGitRepo(repo)
                      TestSupport.commitAll(repo, "Initial commit")

                      val versionSettings = Seq(
                        io.release.ReleaseSharedKeys.releaseIOVersioningReleaseVersion :=
                          ((version: String) => version.stripSuffix("-SNAPSHOT")),
                        io.release.ReleaseSharedKeys.releaseIOVersioningNextVersion    :=
                          ((_: String) => "0.2.0-SNAPSHOT")
                      )
                      val projects        = Seq(
                        MonorepoSpecSupport.monorepoRootProject(
                          repo,
                          projectIds = Seq("core", "api"),
                          settings = Seq(
                            io.release.ReleaseSharedKeys.releaseIOVcsIgnoreUntrackedFiles := true
                          )
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
