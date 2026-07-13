package io.release.monorepo.internal.steps

import cats.effect.IO
import cats.effect.Resource
import io.release.ReleaseManifestMetadata
import io.release.ReleaseSharedKeys
import io.release.TestAssertions.assertIllegalStateMessage
import io.release.TestSupport
import io.release.monorepo.MonorepoContext
import io.release.monorepo.MonorepoReleasePlugin
import io.release.monorepo.MonorepoSpecSupport
import io.release.monorepo.PublishPreparationTestVcs
import io.release.monorepo.internal.MonorepoComposer
import io.release.monorepo.internal.steps.*
import io.release.runtime.ReleaseLogPrefixes
import io.release.runtime.HookPhases
import io.release.runtime.engine.BuiltInStepRole
import io.release.runtime.engine.ProcessStep
import io.release.runtime.sbt.SbtRuntime
import io.release.runtime.workflow.PublishValidation
import munit.CatsEffectSuite
import sbt.*
import sbt.Keys.*
import sbt.Resolver

import java.io.ByteArrayOutputStream
import java.io.File
import java.util.concurrent.atomic.AtomicInteger

class MonorepoPublishArtifactsSpec extends CatsEffectSuite with MonorepoPublishStepsSpecSupport {

  test("publishArtifacts.validate - fail when checks are enabled and publishTo is empty") {
    singleProjectFixtureResource(
      "monorepo-publish-validate-fail",
      rootSettings = Seq(
        MonorepoReleasePlugin.autoImport.releaseIOMonorepoPublishChecks := true
      )
    ) { _ =>
      Seq(
        publish / skip := false,
        publishTo      := None
      )
    }.use { fixture =>
      val ctx     = fixture.context(Seq("core"))
      val project = fixture.projectInfo("core")

      assertIllegalStateMessage(
        MonorepoPublishSteps.publishArtifacts.validate(ctx, project),
        PublishValidation.message("core")
      )
    }
  }

  test(
    "publishArtifacts.validate - treat publish / skip eval error as not skipped " +
      "(pass when publishTo is set)"
  ) {
    singleProjectFixtureResource(
      "monorepo-publish-validate-throw-skip",
      rootSettings = Seq(
        MonorepoReleasePlugin.autoImport.releaseIOMonorepoPublishChecks := true
      )
    ) { projectBase =>
      Seq(
        MonorepoStepTestCompat.throwingPublishSkipSetting,
        publishTo := Some(Resolver.file("local-test", projectBase.getParentFile))
      )
    }.use { fixture =>
      val buffered      = MonorepoPublishArtifactsSpec.bufferedFixture(fixture)
      val ctx           = buffered.fixture.context(Seq("core"))
      val project       = buffered.fixture.projectInfo("core")
      val warningPrefix =
        s"${ReleaseLogPrefixes.Monorepo} Failed to evaluate publish / skip for core: "

      for {
        _   <- MonorepoPublishSteps.publishArtifacts.validate(ctx, project)
        log <- IO.blocking(buffered.consoleBuffer.toString("UTF-8"))
      } yield {
        assertEquals(TestSupport.warningCount(log, warningPrefix), 1)
      }
    }
  }

  test(
    "publishArtifacts.validate - fail with publishTo error when CLI release-version override " +
      "is present and publish/skip := isSnapshot.value (overlay engages, catches the bypass)"
  ) {
    singleProjectFixtureResource(
      "monorepo-publish-validate-isSnapshot-override",
      rootSettings = Seq(
        MonorepoReleasePlugin.autoImport.releaseIOMonorepoPublishChecks := true
      )
    ) { _ =>
      Seq(
        version        := "0.1.0-SNAPSHOT",
        // Use the version-dependent skip pattern explicitly (the `isSnapshot`
        // setting isn't always wired by the minimal test loader; expressing the
        // logic directly mirrors what `publish / skip := isSnapshot.value` evaluates
        // to in a real build).
        publish / skip := version.value.endsWith("-SNAPSHOT"),
        // Mirror real "publishTo not configured" with an explicit None so the
        // publishTo task evaluates cleanly to empty (rather than failing to load).
        publishTo      := None
      )
    }.use { fixture =>
      // Mirror the production CLI-override flow: `applyVersionOverrides` populates
      // `project.versions` with the override before main-segment validate runs.
      val ctx     = fixture.context(
        Seq("core"),
        versionsById = Map("core" -> ("1.0.0" -> ""))
      )
      val project = fixture.projectInfo("core")

      assertIllegalStateMessage(
        MonorepoPublishSteps.publishArtifacts.validate(ctx, project),
        PublishValidation.message("core")
      )
    }
  }

  test(
    "publishArtifacts.validate - leave ctx.state unchanged after the local overlay " +
      "(regression: the validate-time overlay must not leak into execute)"
  ) {
    singleProjectFixtureResource(
      "monorepo-publish-validate-no-leak",
      rootSettings = Seq(
        MonorepoReleasePlugin.autoImport.releaseIOMonorepoPublishChecks := true
      )
    ) { projectBase =>
      Seq(
        version        := "0.1.0-SNAPSHOT",
        publish / skip := false,
        publishTo      := Some(
          sbt.Resolver.file("local", new File(projectBase.getParentFile, "repo"))
        )
      )
    }.use { fixture =>
      val ctx     = fixture.context(
        Seq("core"),
        versionsById = Map("core" -> ("1.0.0" -> ""))
      )
      val project = fixture.projectInfo("core")
      val ref     = fixture.refsById("core")

      // Before validate: project.ref/version reflects the build setting (snapshot).
      val before = _root_.io.release.runtime.sbt.SbtRuntime.extracted(ctx.state).get(ref / version)
      assertEquals(before, "0.1.0-SNAPSHOT")

      MonorepoPublishSteps.publishArtifacts.validate(ctx, project).map { updated =>
        // The transient overlay was discarded; ctx.state is the original snapshot.
        val after =
          _root_.io.release.runtime.sbt.SbtRuntime.extracted(updated.state).get(ref / version)
        assertEquals(after, "0.1.0-SNAPSHOT")
      }
    }
  }

  test("publishArtifacts.validate - bypass checks when disabled or publish is globally skipped") {
    val checksDisabled = singleProjectFixtureResource(
      "monorepo-publish-validate-disabled",
      rootSettings = Seq(
        MonorepoReleasePlugin.autoImport.releaseIOMonorepoPublishChecks := false
      )
    ) { projectBase =>
      Seq(
        MonorepoStepTestCompat.failureCommandPublishSkipSetting(
          new File(projectBase.getParentFile, "disabled-skip-evaluated.txt")
        ),
        publishTo := None
      )
    }

    val skipPublish = singleProjectFixtureResource(
      "monorepo-publish-validate-skip",
      rootSettings = Seq(
        MonorepoReleasePlugin.autoImport.releaseIOMonorepoPublishChecks := true
      )
    ) { _ =>
      Seq(
        publish / skip := false,
        publishTo      := None
      )
    }

    Resource.both(checksDisabled, skipPublish).use { case (disabledFixture, skippedFixture) =>
      val disabledCtx     = disabledFixture.context(Seq("core"))
      val disabledProject = disabledFixture.projectInfo("core")
      val disabledProbe   = new File(disabledFixture.dir, "disabled-skip-evaluated.txt")
      val skippedCtx      = skippedFixture.context(Seq("core"), skipPublish = true)
      val skippedProject  = skippedFixture.projectInfo("core")

      for {
        _             <- MonorepoPublishSteps.publishArtifacts.validate(
                           disabledCtx,
                           disabledProject
                         )
        skipEvaluated <- IO.blocking(disabledProbe.exists())
        _              = assert(!skipEvaluated)
        _             <- MonorepoPublishSteps.publishArtifacts.validate(skippedCtx, skippedProject)
      } yield ()
    }
  }

  test("publishArtifacts.execute - skip the publish task when publish / skip is true") {
    singleProjectFixtureResource("monorepo-publish-skip-action") { _ =>
      Seq(
        publish / skip                           := true,
        ReleaseSharedKeys.releaseIOPublishAction := {
          throw new RuntimeException("publish action should not run")
        }
      )
    }.use { fixture =>
      val ctx     = fixture.context(Seq("core"))
      val project = fixture.projectInfo("core")

      MonorepoPublishSteps.publishArtifacts.execute(ctx, project).map { result =>
        assert(!new File(fixture.dir, "published.txt").exists())
        // publishExecutedKeys becomes `Some(...)` so after-publish hooks know
        // the publish step ran, but the per-project key is *not* recorded
        // because the actual publish task was skipped.
        val recorded = result.publishExecutedKeys.getOrElse(Set.empty)
        assertEquals(recorded, Set.empty[String])
      }
    }
  }

  test("publishArtifacts.execute - record the project key when publish actually runs") {
    singleProjectFixtureResource("monorepo-publish-records-outcome") { projectBase =>
      Seq(
        publish / skip                           := false,
        publishTo                                := Some(Resolver.file("local-test", projectBase.getParentFile)),
        ReleaseSharedKeys.releaseIOPublishAction := { /* no-op publish */ }
      )
    }.use { fixture =>
      val ctx     = fixture.context(Seq("core"))
      val project = fixture.projectInfo("core")

      MonorepoPublishSteps.publishArtifacts.execute(ctx, project).map { result =>
        val expectedKey = MonorepoPublishSteps.publishGateKey(result, project)
        assert(
          result.publishExecutedKeys.exists(_.contains(expectedKey)),
          s"Expected publishExecutedKeys to contain $expectedKey, got ${result.publishExecutedKeys}"
        )
      }
    }
  }

  test(
    "publishArtifacts.execute - mark started but not record key when ctx.skipPublish is true"
  ) {
    singleProjectFixtureResource("monorepo-publish-skip-via-context") { _ =>
      Seq(
        publish / skip                           := false,
        ReleaseSharedKeys.releaseIOPublishAction := {
          throw new RuntimeException("publish action should not run")
        }
      )
    }.use { fixture =>
      val ctx     = fixture.context(Seq("core"), skipPublish = true)
      val project = fixture.projectInfo("core")

      MonorepoPublishSteps.publishArtifacts.execute(ctx, project).map { result =>
        // ctx.skipPublish bypasses publish entirely, so the per-project key is
        // never recorded — but the started marker is set so the after-publish
        // gate distinguishes "publish step ran" from "publish step never ran".
        assertEquals(result.publishExecutedKeys, Some(Set.empty[String]))
      }
    }
  }

  test(
    "publishArtifacts: validate freezes skipPublish=true; execute respects the freeze " +
      "even when a hook flipped ctx.skipPublish back to false"
  ) {
    // No publishTo, so if execute were to honor a hook that flipped skipPublish
    // back to false the publish task would run after validation skipped the
    // publishTo check — the freeze is what prevents that bypass.
    singleProjectFixtureResource(
      "monorepo-publish-freeze-skip-true",
      rootSettings = Seq(
        MonorepoReleasePlugin.autoImport.releaseIOMonorepoPublishChecks := true
      )
    ) { _ =>
      Seq(
        publish / skip                           := false,
        publishTo                                := None,
        ReleaseSharedKeys.releaseIOPublishAction := {
          throw new RuntimeException("publish action should not run")
        }
      )
    }.use { fixture =>
      val ctx     = fixture.context(Seq("core"), skipPublish = true)
      val project = fixture.projectInfo("core")

      for {
        validated  <- MonorepoPublishSteps.publishArtifacts.validate(ctx, project)
        _           = assertEquals(validated.publishSkipFrozen, Some(true))
        hookFlipped = validated.copy(skipPublish = false)
        result     <- MonorepoPublishSteps.publishArtifacts.execute(hookFlipped, project)
        _           = assert(!result.failed)
        _           = assertEquals(result.publishExecutedKeys, Some(Set.empty[String]))
      } yield ()
    }
  }

  test(
    "publishArtifacts: validate freezes skipPublish=false; execute still skips when a hook " +
      "flips ctx.skipPublish to true (preserves the documented hook pattern)"
  ) {
    singleProjectFixtureResource(
      "monorepo-publish-freeze-flip-true",
      rootSettings = Seq(
        MonorepoReleasePlugin.autoImport.releaseIOMonorepoPublishChecks := true
      )
    ) { projectBase =>
      Seq(
        publish / skip                           := false,
        publishTo                                := Some(
          Resolver.file("local", new File(projectBase.getParentFile, "repo"))
        ),
        ReleaseSharedKeys.releaseIOPublishAction := {
          throw new RuntimeException("publish action should not run")
        }
      )
    }.use { fixture =>
      val ctx     = fixture.context(Seq("core"))
      val project = fixture.projectInfo("core")

      for {
        validated  <- MonorepoPublishSteps.publishArtifacts.validate(ctx, project)
        _           = assertEquals(validated.publishSkipFrozen, Some(false))
        hookFlipped = validated.copy(skipPublish = true)
        result     <- MonorepoPublishSteps.publishArtifacts.execute(hookFlipped, project)
        _           = assert(!result.failed)
        _           = assertEquals(result.publishExecutedKeys, Some(Set.empty[String]))
      } yield ()
    }
  }

  test("publishArtifacts: checks-enabled empty validation snapshot denies a later project") {
    singleProjectFixtureResource(
      "monorepo-publish-empty-validation-snapshot",
      rootSettings = Seq(
        MonorepoReleasePlugin.autoImport.releaseIOMonorepoPublishChecks := true
      )
    ) { projectBase =>
      Seq(
        publishTo                                := None,
        ReleaseSharedKeys.releaseIOPublishAction :=
          sbt.IO.write(new File(projectBase.getParentFile, "published.txt"), "published")
      )
    }.use { fixture =>
      val buffered  = MonorepoPublishArtifactsSpec.bufferedFixture(fixture)
      val baseCtx   = buffered.fixture.context(Seq("core"))
      val project   = buffered.fixture.projectInfo("core")
      val ref       = buffered.fixture.refsById("core")
      val marker    = new File(fixture.dir, "published.txt")
      val skipProbe = new File(fixture.dir, "execute-skip-evaluated.txt")
      val warning   = MonorepoPublishArtifactsSpec.unvalidatedIterationWarning("core")
      val initial   = baseCtx.withState(
        TestSupport.appendSessionSettings(
          baseCtx.state,
          Seq(
            MonorepoStepTestCompat.observedPublishSkipSetting(
              ref,
              skipProbe,
              skipped = false
            )
          )
        )
      )

      val selectionBoundary = ProcessStep.Single[MonorepoContext](
        name = "test-selection-boundary",
        execute = IO.pure,
        roles = Set(BuiltInStepRole.SelectionBoundary)
      )
      val emptySelection    = ProcessStep.Single[MonorepoContext](
        name = s"${HookPhases.AfterSelection}:empty-selection",
        execute = ctx => IO.pure(ctx.withProjects(Seq.empty))
      )
      val introduceProject  = ProcessStep.Single[MonorepoContext](
        name = "introduce-project-after-validation",
        execute = ctx => IO.pure(ctx.withProjects(Seq(project)))
      )

      for {
        result        <- MonorepoComposer.compose(
                           Seq(
                             selectionBoundary,
                             emptySelection,
                             introduceProject,
                             MonorepoPublishSteps.publishArtifacts
                           )
                         )(initial)
        published     <- IO.blocking(marker.exists())
        skipEvaluated <- IO.blocking(skipProbe.exists())
        log           <- IO.blocking(buffered.consoleBuffer.toString("UTF-8"))
      } yield {
        assert(result.hasValidatedPublishEligibilitySnapshot)
        assertEquals(result.publishSkipFrozen, Some(false))
        assert(!published)
        assert(!skipEvaluated)
        assertEquals(result.publishExecutedKeys, Some(Set.empty[String]))
        assertEquals(TestSupport.warningCount(log, warning), 1)
      }
    }
  }

  test("publishArtifacts: checks-disabled empty validation keeps a later project live") {
    singleProjectFixtureResource(
      "monorepo-publish-empty-validation-checks-disabled",
      rootSettings = Seq(
        MonorepoReleasePlugin.autoImport.releaseIOMonorepoPublishChecks := false
      )
    ) { projectBase =>
      Seq(
        publishTo                                := None,
        ReleaseSharedKeys.releaseIOPublishAction :=
          sbt.IO.write(new File(projectBase.getParentFile, "published.txt"), "published")
      )
    }.use { fixture =>
      val baseCtx   = fixture.context(Seq("core"))
      val project   = fixture.projectInfo("core")
      val ref       = fixture.refsById("core")
      val marker    = new File(fixture.dir, "published.txt")
      val skipProbe = new File(fixture.dir, "execute-skip-evaluated.txt")
      val initial   = baseCtx.withState(
        TestSupport.appendSessionSettings(
          baseCtx.state,
          Seq(
            MonorepoStepTestCompat.observedPublishSkipSetting(
              ref,
              skipProbe,
              skipped = false
            )
          )
        )
      )

      val selectionBoundary = ProcessStep.Single[MonorepoContext](
        name = "test-selection-boundary",
        execute = IO.pure,
        roles = Set(BuiltInStepRole.SelectionBoundary)
      )
      val emptySelection    = ProcessStep.Single[MonorepoContext](
        name = s"${HookPhases.AfterSelection}:empty-selection",
        execute = ctx => IO.pure(ctx.withProjects(Seq.empty))
      )
      val introduceProject  = ProcessStep.Single[MonorepoContext](
        name = "introduce-project-after-validation",
        execute = ctx => IO.pure(ctx.withProjects(Seq(project)))
      )

      for {
        result        <- MonorepoComposer.compose(
                           Seq(
                             selectionBoundary,
                             emptySelection,
                             introduceProject,
                             MonorepoPublishSteps.publishArtifacts
                           )
                         )(initial)
        published     <- IO.blocking(marker.exists())
        skipEvaluated <- IO.blocking(skipProbe.exists())
      } yield {
        assert(!result.hasValidatedPublishEligibilitySnapshot)
        assertEquals(result.publishSkipFrozen, Some(false))
        assert(published)
        assert(skipEvaluated)
        val executedKey = MonorepoPublishSteps.publishGateKey(result, project)
        assert(result.publishExecutedKeys.exists(_.contains(executedKey)))
      }
    }
  }

  test("publishArtifacts: empty validation freezes global skip before later project introduction") {
    singleProjectFixtureResource(
      "monorepo-publish-empty-validation-freeze-skip",
      rootSettings = Seq(
        MonorepoReleasePlugin.autoImport.releaseIOMonorepoPublishChecks := true
      )
    ) { projectBase =>
      Seq(
        publishTo                                := None,
        ReleaseSharedKeys.releaseIOPublishAction :=
          sbt.IO.write(new File(projectBase.getParentFile, "published.txt"), "published")
      )
    }.use { fixture =>
      val baseCtx   = fixture.context(Seq("core"), skipPublish = true)
      val project   = fixture.projectInfo("core")
      val ref       = fixture.refsById("core")
      val marker    = new File(fixture.dir, "published.txt")
      val skipProbe = new File(fixture.dir, "execute-skip-evaluated.txt")
      val initial   = baseCtx.withState(
        TestSupport.appendSessionSettings(
          baseCtx.state,
          Seq(
            MonorepoStepTestCompat.observedPublishSkipSetting(
              ref,
              skipProbe,
              skipped = false
            )
          )
        )
      )

      val selectionBoundary = ProcessStep.Single[MonorepoContext](
        name = "test-selection-boundary",
        execute = IO.pure,
        roles = Set(BuiltInStepRole.SelectionBoundary)
      )
      val emptySelection    = ProcessStep.Single[MonorepoContext](
        name = s"${HookPhases.AfterSelection}:empty-selection",
        execute = ctx => IO.pure(ctx.withProjects(Seq.empty))
      )
      val introduceProject  = ProcessStep.Single[MonorepoContext](
        name = "introduce-project-and-clear-live-skip",
        execute = ctx => IO.pure(ctx.withProjects(Seq(project)).copy(skipPublish = false))
      )

      for {
        result        <- MonorepoComposer.compose(
                           Seq(
                             selectionBoundary,
                             emptySelection,
                             introduceProject,
                             MonorepoPublishSteps.publishArtifacts
                           )
                         )(initial)
        published     <- IO.blocking(marker.exists())
        skipEvaluated <- IO.blocking(skipProbe.exists())
      } yield {
        assert(result.hasValidatedPublishEligibilitySnapshot)
        assertEquals(result.publishSkipFrozen, Some(true))
        assertEquals(result.skipPublish, false)
        assert(!published)
        assert(!skipEvaluated)
        assertEquals(result.publishExecutedKeys, Some(Set.empty[String]))
      }
    }
  }

  test("publishArtifacts: use overlay-effective Scala version for eligibility and hook keys") {
    singleProjectFixtureResource(
      "monorepo-publish-overlay-scala-identity",
      rootSettings = Seq(
        MonorepoReleasePlugin.autoImport.releaseIOMonorepoPublishChecks := true
      )
    ) { projectBase =>
      Seq(
        version                                  := "0.1.0-SNAPSHOT",
        scalaVersion                             := {
          if (version.value == "1.0.0") TestSupport.alternateScalaVersion
          else TestSupport.CurrentScalaVersion
        },
        publish / skip                           := false,
        publishTo                                := Some(
          Resolver.file("local", new File(projectBase.getParentFile, "repo"))
        ),
        ReleaseSharedKeys.releaseIOPublishAction :=
          sbt.IO.write(new File(projectBase.getParentFile, "published.txt"), "published")
      )
    }.use { fixture =>
      val ctx      = fixture.context(
        Seq("core"),
        versionsById = Map("core" -> ("1.0.0" -> "1.1.0-SNAPSHOT"))
      )
      val project  = ctx.currentProjects.head
      val ref      = project.ref
      val marker   = new File(fixture.dir, "published.txt")
      val entryKey = MonorepoPublishSteps.publishGateKey(ctx, project)

      for {
        gateValidation <- MonorepoPublishSteps.publishGateValidation(ctx, project)
        validationKey   = gateValidation.key
        validated      <- MonorepoPublishSteps.publishArtifacts.validate(
                            gateValidation.context,
                            project
                          )
        validateGate   <- MonorepoPublishSteps.shouldRunPublishHooks(validated, project)
        executeState    = TestSupport.appendSessionSettings(
                            validated.state,
                            Seq(ref / version := "1.0.0")
                          )
        executeCtx      = validated.withState(executeState)
        executeScala    = SbtRuntime.extracted(executeState).get(ref / scalaVersion)
        executeKey      = MonorepoPublishSteps.publishGateKey(executeCtx, project)
        executeGate    <- MonorepoPublishSteps.shouldRunPublishHooksAtExecute(executeCtx, project)
        result         <- MonorepoPublishSteps.publishArtifacts.execute(executeCtx, project)
        published      <- IO.blocking(marker.exists())
      } yield {
        assertEquals(executeScala, TestSupport.alternateScalaVersion)
        assertNotEquals(entryKey, validationKey)
        assertEquals(executeKey, validationKey)
        assertEquals(
          validated.validatedPublishEligibility(ref, TestSupport.alternateScalaVersion),
          Some(true)
        )
        assertEquals(
          validated.validatedPublishEligibility(ref, TestSupport.CurrentScalaVersion),
          None
        )
        val probe = validated
          .publishValidationProbe(
            MonorepoContext.PublishIteration(ref, TestSupport.CurrentScalaVersion)
          )
          .getOrElse(fail("expected shared publish validation probe"))
        assert(probe.targetValidated)
        assertEquals(probe.pendingTargetState, None)
        assert(validateGate)
        assert(executeGate)
        assert(published)
        assert(result.publishExecutedKeys.exists(_.contains(executeKey)))
      }
    }
  }

  test("publishGateValidation resolves its release-version overlay once") {
    singleProjectFixtureResource("monorepo-publish-gate-single-overlay") { projectBase =>
      sbt.IO.write(
        new File(projectBase, "version.sbt"),
        """version := "0.1.0-SNAPSHOT"""" + "\n"
      )
      Seq(
        version        := "0.1.0-SNAPSHOT",
        scalaVersion   := {
          if (version.value == "0.1.0") TestSupport.alternateScalaVersion
          else TestSupport.CurrentScalaVersion
        },
        publish / skip := false
      )
    }.use { fixture =>
      val baseCtx      = fixture.context(Seq("core"))
      val project      = fixture.projectInfo("core")
      val releaseCalls = new AtomicInteger(0)
      val nextCalls    = new AtomicInteger(0)
      val ctx          = baseCtx.withState(
        TestSupport.appendSessionSettings(
          baseCtx.state,
          MonorepoStepTestCompat.countedVersionTaskSettings(
            project.ref,
            releaseCalls,
            nextCalls
          )
        )
      )

      MonorepoPublishSteps.publishGateValidation(ctx, project).map { resolved =>
        assert(resolved.decision)
        assert(resolved.key.nonEmpty)
        assertEquals(releaseCalls.get(), 1)
        assertEquals(nextCalls.get(), 1)
      }
    }
  }

  test("publish validation snapshot preserves marked refresh probes and clears stale probes") {
    singleProjectFixtureResource(
      "monorepo-publish-refresh-probe-snapshot",
      rootSettings = Seq(
        MonorepoReleasePlugin.autoImport.releaseIOMonorepoPublishChecks := false
      )
    ) { projectBase =>
      Seq(
        scalaVersion := TestSupport.CurrentScalaVersion,
        MonorepoStepTestCompat.countedPublishSkipSetting(
          new File(projectBase.getParentFile, "publish-skip-evaluations.txt"),
          skipped = false
        ),
        publishTo    := Some(Resolver.file("local", projectBase.getParentFile))
      )
    }.use { fixture =>
      val ctx       = fixture.context(Seq("core"))
      val project   = fixture.projectInfo("core")
      val rootRef   = fixture.refsById("root")
      val iteration = MonorepoContext.PublishIteration(
        project.ref,
        TestSupport.CurrentScalaVersion
      )
      val counter   = new File(fixture.dir, "publish-skip-evaluations.txt")

      for {
        hookGate      <- MonorepoPublishSteps.beforePublishGateValidation(ctx, project)
        checksEnabled  =
          hookGate.context.withState(
            TestSupport.appendSessionSettings(
              hookGate.context.state,
              Seq(
                rootRef /
                  MonorepoReleasePlugin.autoImport.releaseIOMonorepoPublishChecks := true
              )
            )
          )
        marked         = checksEnabled.beginPublishValidationBatch(
                           refreshExecutedPrelude = true
                         )
        prepared      <- MonorepoPublishSteps.preparePublishValidation(marked)
        directSnapshot = checksEnabled.initializeValidatedPublishEligibilitySnapshot(
                           preserveRefreshProbes = false
                         )
        validated     <- MonorepoPublishSteps.publishArtifacts.validate(prepared, project)
        evaluations   <- IO.blocking(sbt.IO.read(counter).trim.toInt)
      } yield {
        assertEquals(hookGate.decision, true)
        assert(!hookGate.context.hasValidatedPublishEligibilitySnapshot)
        assert(marked.hasPendingPublishValidationRefreshInputs)
        assert(prepared.hasPendingPublishValidationRefreshInputs)
        assert(prepared.publishValidationProbe(iteration).nonEmpty)
        assertEquals(directSnapshot.publishValidationProbe(iteration), None)
        assertEquals(directSnapshot.validatedPublishGateDecision(iteration), None)
        assert(!validated.hasPendingPublishValidationRefreshInputs)
        assertEquals(validated.validatedPublishGateDecision(iteration), Some(true))
        assertEquals(validated.validatedPublishEligibility(iteration), Some(true))
        val refreshedProbe = validated
          .publishValidationProbe(iteration)
          .getOrElse(fail("expected the consumed refresh probe"))
        assert(refreshedProbe.targetValidated)
        assertEquals(refreshedProbe.pendingTargetState, None)
        assertEquals(evaluations, 2)
      }
    }
  }

  test("publish validation batch preserves an open hook probe and resets a finalized batch") {
    singleProjectFixtureResource(
      "monorepo-publish-validation-batch-reset",
      rootSettings = Seq(
        MonorepoReleasePlugin.autoImport.releaseIOMonorepoPublishChecks := true
      )
    ) { projectBase =>
      Seq(
        scalaVersion := TestSupport.CurrentScalaVersion,
        MonorepoStepTestCompat.firstPublishSkipEvaluationReturnsTrue(
          new File(projectBase.getParentFile, "publish-skip-evaluations.txt")
        ),
        publishTo    := Some(Resolver.file("local", projectBase.getParentFile))
      )
    }.use { fixture =>
      val ctx       = fixture.context(Seq("core"))
      val project   = fixture.projectInfo("core")
      val iteration = MonorepoContext.PublishIteration(
        project.ref,
        TestSupport.CurrentScalaVersion
      )
      val probe     = new File(fixture.dir, "publish-skip-evaluations.txt")
      val prepared  = MonorepoComposer
        .preparedSteps(Seq(MonorepoPublishSteps.publishArtifacts), crossBuild = false)
        .head

      for {
        hookGate          <- MonorepoPublishSteps.beforePublishGateValidation(ctx, project)
        firstValidated    <- prepared.validate(hookGate.context)
        firstEvaluations  <- IO.blocking(sbt.IO.read(probe))
        secondHookGate    <- MonorepoPublishSteps.beforePublishGateValidation(
                               firstValidated,
                               project
                             )
        secondValidated   <- prepared.validate(secondHookGate.context)
        secondEvaluations <- IO.blocking(sbt.IO.read(probe))
      } yield {
        assertEquals(hookGate.decision, false)
        assertEquals(firstEvaluations, "1")
        assert(firstValidated.publishValidationFinalized)
        assertEquals(firstValidated.validatedPublishEligibility(iteration), Some(false))
        assertEquals(secondHookGate.decision, true)
        assertEquals(secondEvaluations, "2")
        assert(secondValidated.publishValidationFinalized)
        assertEquals(secondValidated.validatedPublishEligibility(iteration), Some(true))
        val secondProbe = secondValidated
          .publishValidationProbe(iteration)
          .getOrElse(fail("expected fresh second-batch probe"))
        assert(secondProbe.targetValidated)
        assertEquals(secondProbe.pendingTargetState, None)
      }
    }
  }

  test("publish validation batch resets completed checks-disabled hook probes") {
    singleProjectFixtureResource(
      "monorepo-publish-validation-disabled-batch-reset",
      rootSettings = Seq(
        MonorepoReleasePlugin.autoImport.releaseIOMonorepoPublishChecks := false
      )
    ) { projectBase =>
      Seq(
        scalaVersion := TestSupport.CurrentScalaVersion,
        MonorepoStepTestCompat.firstPublishSkipEvaluationReturnsTrue(
          new File(projectBase.getParentFile, "publish-skip-evaluations.txt")
        ),
        publishTo    := Some(Resolver.file("local", projectBase.getParentFile))
      )
    }.use { fixture =>
      val ctx      = fixture.context(Seq("core"))
      val project  = fixture.projectInfo("core")
      val probe    = new File(fixture.dir, "publish-skip-evaluations.txt")
      val prepared = MonorepoComposer
        .preparedSteps(Seq(MonorepoPublishSteps.publishArtifacts), crossBuild = false)
        .head

      for {
        firstHook       <- MonorepoPublishSteps.beforePublishGateValidation(ctx, project)
        firstValidated  <- prepared.validate(firstHook.context)
        secondHook      <- MonorepoPublishSteps.beforePublishGateValidation(
                             firstValidated,
                             project
                           )
        secondValidated <- prepared.validate(secondHook.context)
        evaluationCount <- IO.blocking(sbt.IO.read(probe))
      } yield {
        assertEquals(firstHook.decision, false)
        assert(firstValidated.publishValidationFinalized)
        assert(!firstValidated.hasValidatedPublishEligibilitySnapshot)
        assertEquals(secondHook.decision, true)
        assert(secondValidated.publishValidationFinalized)
        assert(!secondValidated.hasValidatedPublishEligibilitySnapshot)
        assertEquals(evaluationCount, "2")
      }
    }
  }

  test("publish validation batch re-freezes checks-disabled skipPublish") {
    singleProjectFixtureResource(
      "monorepo-publish-validation-disabled-refreeze",
      rootSettings = Seq(
        MonorepoReleasePlugin.autoImport.releaseIOMonorepoPublishChecks := false
      )
    ) { projectBase =>
      Seq(
        publish / skip := false,
        publishTo      := Some(Resolver.file("local", projectBase.getParentFile))
      )
    }.use { fixture =>
      val project  = fixture.projectInfo("core")
      val initial  = fixture.context(Seq("core"), skipPublish = true)
      val prepared = MonorepoComposer
        .preparedSteps(Seq(MonorepoPublishSteps.publishArtifacts), crossBuild = false)
        .head

      for {
        firstHook       <- MonorepoPublishSteps.beforePublishGateValidation(initial, project)
        firstValidated  <- prepared.validate(firstHook.context)
        secondHook      <- MonorepoPublishSteps.beforePublishGateValidation(
                             firstValidated.copy(skipPublish = false),
                             project
                           )
        secondValidated <- prepared.validate(secondHook.context)
      } yield {
        assertEquals(firstHook.decision, false)
        assert(firstValidated.publishValidationFinalized)
        assertEquals(secondHook.decision, true)
        assert(secondValidated.publishValidationFinalized)
      }
    }
  }

  test("empty checks-disabled publish validation still completes its batch") {
    singleProjectFixtureResource(
      "monorepo-publish-validation-disabled-empty",
      rootSettings = Seq(
        MonorepoReleasePlugin.autoImport.releaseIOMonorepoPublishChecks := false
      )
    ) { projectBase =>
      Seq(
        publish / skip := false,
        publishTo      := Some(Resolver.file("local", projectBase.getParentFile))
      )
    }.use { fixture =>
      val populated = fixture.context(Seq("core"))
      val project   = fixture.projectInfo("core")
      val empty     = populated.withProjects(Seq.empty).copy(skipPublish = true)
      val prepared  = MonorepoComposer
        .preparedSteps(Seq(MonorepoPublishSteps.publishArtifacts), crossBuild = false)
        .head

      for {
        firstValidated <- prepared.validate(empty)
        secondHook     <- MonorepoPublishSteps.beforePublishGateValidation(
                            firstValidated
                              .withProjects(Seq(project))
                              .copy(skipPublish = false),
                            project
                          )
      } yield {
        assert(firstValidated.publishValidationFinalized)
        assert(!firstValidated.hasValidatedPublishEligibilitySnapshot)
        assertEquals(secondHook.decision, true)
      }
    }
  }

  test("repeated before-publish validation re-freezes the run-level skip decision") {
    singleProjectFixtureResource(
      "monorepo-publish-validation-refreeze-skip",
      rootSettings = Seq(
        MonorepoReleasePlugin.autoImport.releaseIOMonorepoPublishChecks := true
      )
    ) { projectBase =>
      Seq(
        publish / skip := false,
        publishTo      := Some(Resolver.file("local", projectBase.getParentFile))
      )
    }.use { fixture =>
      val project  = fixture.projectInfo("core")
      val marker   = new File(fixture.dir, "publish-skip-evaluated.txt")
      val initial  = fixture
        .context(Seq("core"), skipPublish = true)
        .withState(
          TestSupport.appendSessionSettings(
            fixture.state,
            Seq(
              MonorepoStepTestCompat.observedPublishSkipSetting(
                project.ref,
                marker,
                skipped = false
              )
            )
          )
        )
      val prepared = MonorepoComposer
        .preparedSteps(Seq(MonorepoPublishSteps.publishArtifacts), crossBuild = false)
        .head

      for {
        firstHook       <- MonorepoPublishSteps.beforePublishGateValidation(initial, project)
        firstValidated  <- prepared.validate(firstHook.context)
        firstEvaluated  <- IO.blocking(marker.exists())
        secondHook      <- MonorepoPublishSteps.beforePublishGateValidation(
                             firstValidated.copy(skipPublish = false),
                             project
                           )
        secondValidated <- prepared.validate(secondHook.context)
        secondEvaluated <- IO.blocking(marker.exists())
      } yield {
        assertEquals(firstHook.decision, false)
        assert(!firstEvaluated)
        assertEquals(secondHook.decision, true)
        assert(secondEvaluated)
        assert(secondValidated.publishValidationFinalized)
      }
    }
  }

  test("publishArtifacts: checks-enabled validation rejects Scala drift from publish / skip") {
    singleProjectFixtureResource(
      "monorepo-publish-skip-scala-drift-validation",
      rootSettings = Seq(
        MonorepoReleasePlugin.autoImport.releaseIOMonorepoPublishChecks := true
      )
    ) { projectBase =>
      Seq(
        scalaVersion   := TestSupport.CurrentScalaVersion,
        publish / skip := false,
        MonorepoStepTestCompat.failureCommandPublishTargetSetting(
          new File(projectBase.getParentFile, "publish-target-evaluated.txt")
        )
      )
    }.use { fixture =>
      val ctx         = fixture.context(Seq("core"))
      val project     = fixture.projectInfo("core")
      val ref         = fixture.refsById("core")
      val targetProbe = new File(fixture.dir, "publish-target-evaluated.txt")
      val drifted     = ctx.withState(
        TestSupport.appendSessionSettings(
          ctx.state,
          Seq(
            MonorepoStepTestCompat.publishSkipWithScalaStateMutation(
              ref,
              TestSupport.alternateScalaVersion,
              skipped = false
            )
          )
        )
      )
      val message     = MonorepoPublishArtifactsSpec.publishSkipScalaDriftMessage(
        "core",
        TestSupport.CurrentScalaVersion,
        TestSupport.alternateScalaVersion
      )

      for {
        _               <- assertIllegalStateMessage(
                             MonorepoPublishSteps.publishArtifacts.validate(drifted, project),
                             message
                           )
        targetEvaluated <- IO.blocking(targetProbe.exists())
      } yield assert(!targetEvaluated)
    }
  }

  test("publishArtifacts: late Scala drift from publish / skip aborts before publish") {
    singleProjectFixtureResource(
      "monorepo-publish-skip-scala-drift-execute",
      rootSettings = Seq(
        MonorepoReleasePlugin.autoImport.releaseIOMonorepoPublishChecks := true
      )
    ) { projectBase =>
      Seq(
        scalaVersion                             := TestSupport.CurrentScalaVersion,
        publish / skip                           := false,
        publishTo                                := Some(
          Resolver.file("local", new File(projectBase.getParentFile, "repo"))
        ),
        ReleaseSharedKeys.releaseIOPublishAction :=
          sbt.IO.touch(new File(projectBase.getParentFile, "published.txt"))
      )
    }.use { fixture =>
      val ctx     = fixture.context(Seq("core"))
      val project = fixture.projectInfo("core")
      val ref     = fixture.refsById("core")
      val marker  = new File(fixture.dir, "published.txt")
      val message = MonorepoPublishArtifactsSpec.publishSkipScalaDriftMessage(
        "core",
        TestSupport.CurrentScalaVersion,
        TestSupport.alternateScalaVersion
      )

      for {
        validated   <- MonorepoPublishSteps.publishArtifacts.validate(ctx, project)
        executeState = TestSupport.appendSessionSettings(
                         validated.state,
                         Seq(
                           MonorepoStepTestCompat.publishSkipWithScalaStateMutation(
                             ref,
                             TestSupport.alternateScalaVersion,
                             skipped = false
                           )
                         )
                       )
        result      <- MonorepoPublishSteps.publishArtifacts
                         .execute(validated.withState(executeState), project)
                         .attempt
        published   <- IO.blocking(marker.exists())
      } yield {
        assertEquals(result.left.map(_.getMessage), Left(message))
        assert(!published)
      }
    }
  }

  test("publishArtifacts: checks-enabled metadata preparation cannot change Scala iteration") {
    singleProjectFixtureResource(
      "monorepo-publish-preparation-scala-drift",
      rootSettings = Seq(
        MonorepoReleasePlugin.autoImport.releaseIOMonorepoPublishChecks := true
      )
    ) { projectBase =>
      Seq(
        ReleaseManifestMetadata.releaseIOInternalReleaseHash := None,
        scalaVersion                                         :=
          ReleaseManifestMetadata.releaseIOInternalReleaseHash.value.fold(
            TestSupport.CurrentScalaVersion
          )(_ => TestSupport.alternateScalaVersion),
        publish / skip                                       := false,
        publishTo                                            := Some(
          Resolver.file("local", new File(projectBase.getParentFile, "repo"))
        ),
        ReleaseSharedKeys.releaseIOPublishAction             :=
          sbt.IO.touch(new File(projectBase.getParentFile, "published.txt"))
      )
    }.use { fixture =>
      val ctx     = fixture.context(
        Seq("core"),
        versionsById = Map("core" -> ("1.0.0" -> "1.1.0-SNAPSHOT")),
        vcs = Some(new PublishPreparationTestVcs(fixture.dir))
      )
      val project = ctx.currentProjects.head
      val marker  = new File(fixture.dir, "published.txt")
      val message = MonorepoPublishArtifactsSpec.publishPreparationScalaDriftMessage(
        "core",
        TestSupport.CurrentScalaVersion,
        TestSupport.alternateScalaVersion
      )

      for {
        validated <- MonorepoPublishSteps.publishArtifacts.validate(ctx, project)
        result    <- MonorepoPublishSteps.publishArtifacts.execute(validated, project).attempt
        published <- IO.blocking(marker.exists())
      } yield {
        assertEquals(result.left.map(_.getMessage), Left(message))
        assert(!published)
      }
    }
  }

  test("publishArtifacts: checks-disabled execution preserves Scala-mutating skip fallback") {
    singleProjectFixtureResource(
      "monorepo-publish-skip-scala-drift-disabled",
      rootSettings = Seq(
        MonorepoReleasePlugin.autoImport.releaseIOMonorepoPublishChecks := false
      )
    ) { projectBase =>
      Seq(
        scalaVersion                             := TestSupport.CurrentScalaVersion,
        publish / skip                           := false,
        ReleaseSharedKeys.releaseIOPublishAction :=
          sbt.IO.touch(new File(projectBase.getParentFile, "published.txt"))
      )
    }.use { fixture =>
      val ctx          = fixture.context(Seq("core"))
      val project      = fixture.projectInfo("core")
      val ref          = fixture.refsById("core")
      val marker       = new File(fixture.dir, "published.txt")
      val drifted      = ctx.withState(
        TestSupport.appendSessionSettings(
          ctx.state,
          Seq(
            MonorepoStepTestCompat.publishSkipWithScalaStateMutation(
              ref,
              TestSupport.alternateScalaVersion,
              skipped = false
            ),
            MonorepoStepTestCompat.publishActionWithScalaStateMutation(
              ref,
              "9.9.9",
              marker
            )
          )
        )
      )
      val attempt      = MonorepoContext.PublishIteration(
        ref,
        TestSupport.CurrentScalaVersion
      )
      val actionSource = MonorepoContext.PublishIteration(
        ref,
        TestSupport.alternateScalaVersion
      )

      for {
        validated <- MonorepoPublishSteps.publishArtifacts.validate(drifted, project)
        result    <- MonorepoPublishSteps.publishArtifacts.execute(validated, project)
        published <- IO.blocking(marker.exists())
        liveScala  = SbtRuntime.extracted(result.state).get(ref / scalaVersion)
      } yield {
        assert(!validated.hasValidatedPublishEligibilitySnapshot)
        assert(published)
        assertEquals(liveScala, "9.9.9")
        assertEquals(
          MonorepoPublishSteps.afterPublishGateKey(result, project),
          attempt.gateKey
        )
        assert(MonorepoPublishSteps.didPublishForAfterHook(result, project))
        assert(result.publishExecutedKeys.exists(_.contains(actionSource.gateKey)))
      }
    }
  }

  test("publishArtifacts: successful publish Scala drift retains its source hook iteration") {
    singleProjectFixtureResource(
      "monorepo-publish-action-scala-drift",
      rootSettings = Seq(
        MonorepoReleasePlugin.autoImport.releaseIOMonorepoPublishChecks := true
      )
    ) { projectBase =>
      Seq(
        scalaVersion   := TestSupport.CurrentScalaVersion,
        publish / skip := false,
        publishTo      := Some(Resolver.file("local", new File(projectBase.getParentFile, "repo")))
      )
    }.use { fixture =>
      val ctx      = fixture.context(Seq("core"))
      val project  = fixture.projectInfo("core")
      val ref      = fixture.refsById("core")
      val marker   = new File(fixture.dir, "published.txt")
      val entryKey = MonorepoPublishSteps.publishGateKey(ctx, project)
      val withTask = ctx.withState(
        TestSupport.appendSessionSettings(
          ctx.state,
          Seq(
            MonorepoStepTestCompat.publishActionWithScalaStateMutation(
              ref,
              TestSupport.alternateScalaVersion,
              marker
            )
          )
        )
      )

      for {
        validated <- MonorepoPublishSteps.publishArtifacts.validate(withTask, project)
        result    <- MonorepoPublishSteps.publishArtifacts.execute(validated, project)
        published <- IO.blocking(marker.exists())
        liveScala  = SbtRuntime.extracted(result.state).get(ref / scalaVersion)
      } yield {
        assert(published)
        assertEquals(liveScala, TestSupport.alternateScalaVersion)
        assertNotEquals(MonorepoPublishSteps.publishGateKey(result, project), entryKey)
        assertEquals(MonorepoPublishSteps.afterPublishGateKey(result, project), entryKey)
        assert(MonorepoPublishSteps.didPublishForAfterHook(result, project))
        assert(result.publishExecutedKeys.exists(_.contains(entryKey)))
      }
    }
  }

  test(
    "publishArtifacts: validated publish / skip=true remains an upper bound when execute " +
      "sees false"
  ) {
    singleProjectFixtureResource(
      "monorepo-publish-freeze-project-skip-true",
      rootSettings = Seq(
        MonorepoReleasePlugin.autoImport.releaseIOMonorepoPublishChecks := true
      )
    ) { projectBase =>
      val marker      = new File(projectBase.getParentFile, "published.txt")
      val probeMarker = new File(projectBase.getParentFile, "publish-skip-evaluations.txt")

      Seq(
        MonorepoStepTestCompat.firstPublishSkipEvaluationReturnsTrue(probeMarker),
        publishTo                                := None,
        ReleaseSharedKeys.releaseIOPublishAction := sbt.IO.write(marker, "published")
      )
    }.use { fixture =>
      val ctx     = fixture.context(Seq("core"))
      val project = fixture.projectInfo("core")
      val ref     = fixture.refsById("core")
      val marker  = new File(fixture.dir, "published.txt")
      val probe   = new File(fixture.dir, "publish-skip-evaluations.txt")

      for {
        validated     <- MonorepoPublishSteps.publishArtifacts.validate(ctx, project)
        validatedScala =
          SbtRuntime.extracted(validated.state).getOpt(ref / scalaVersion).getOrElse("")
        _              = assertEquals(
                           validated.validatedPublishEligibility(ref, validatedScala),
                           Some(false)
                         )
        initialProbes <- IO.blocking(sbt.IO.read(probe))
        _              = assertEquals(initialProbes, "1")
        validateGate  <- MonorepoPublishSteps.shouldRunPublishHooks(validated, project)
        executeGate   <- MonorepoPublishSteps.shouldRunPublishHooksAtExecute(validated, project)
        _              = assertEquals(validateGate, false)
        _              = assertEquals(executeGate, false)
        result        <- MonorepoPublishSteps.publishArtifacts.execute(validated, project)
        published     <- IO.blocking(marker.exists())
        finalProbes   <- IO.blocking(sbt.IO.read(probe))
        _              = assert(!published)
        _              = assertEquals(finalProbes, "1")
        _              = assertEquals(result.publishExecutedKeys, Some(Set.empty[String]))
      } yield ()
    }
  }

  test("publishArtifacts: deny a Scala iteration introduced after publish validation") {
    singleProjectFixtureResource(
      "monorepo-publish-unvalidated-scala-iteration",
      rootSettings = Seq(
        MonorepoReleasePlugin.autoImport.releaseIOMonorepoPublishChecks := true
      )
    ) { projectBase =>
      val marker = new File(projectBase.getParentFile, "published.txt")

      Seq(
        publish / skip                           := false,
        publishTo                                := Some(
          Resolver.file("local", new File(projectBase.getParentFile, "repo"))
        ),
        ReleaseSharedKeys.releaseIOPublishAction := sbt.IO.write(marker, "published")
      )
    }.use { fixture =>
      val buffered  = MonorepoPublishArtifactsSpec.bufferedFixture(fixture)
      val ctx       = buffered.fixture.context(Seq("core"))
      val project   = buffered.fixture.projectInfo("core")
      val ref       = buffered.fixture.refsById("core")
      val marker    = new File(fixture.dir, "published.txt")
      val skipProbe = new File(fixture.dir, "execute-skip-evaluated.txt")
      val warning   = MonorepoPublishArtifactsSpec.unvalidatedIterationWarning("core")

      for {
        validated     <- MonorepoPublishSteps.publishArtifacts.validate(ctx, project)
        validatedScala =
          SbtRuntime.extracted(validated.state).getOpt(ref / scalaVersion).getOrElse("")
        _              = assertEquals(
                           validated.validatedPublishEligibility(ref, validatedScala),
                           Some(true)
                         )
        executeState   = TestSupport.appendSessionSettings(
                           validated.state,
                           Seq(
                             ref / scalaVersion := TestSupport.alternateScalaVersion,
                             MonorepoStepTestCompat.observedPublishSkipSetting(
                               ref,
                               skipProbe,
                               skipped = false
                             )
                           )
                         )
        executeCtx     = validated.withState(executeState)
        validateGate  <- MonorepoPublishSteps.shouldRunPublishHooks(executeCtx, project)
        executeGate   <- MonorepoPublishSteps.shouldRunPublishHooksAtExecute(executeCtx, project)
        _              = assertEquals(validateGate, false)
        _              = assertEquals(executeGate, false)
        result        <- MonorepoPublishSteps.publishArtifacts.execute(executeCtx, project)
        published     <- IO.blocking(marker.exists())
        skipEvaluated <- IO.blocking(skipProbe.exists())
        log           <- IO.blocking(buffered.consoleBuffer.toString("UTF-8"))
        _              = assert(!published)
        _              = assert(!skipEvaluated)
        _              = assertEquals(result.publishExecutedKeys, Some(Set.empty[String]))
        _              = assertEquals(TestSupport.warningCount(log, warning), 1)
      } yield ()
    }
  }

  test("publishArtifacts: deny a project introduced after publish validation") {
    twoProjectFixtureResource(
      "monorepo-publish-unvalidated-project",
      rootSettings = Seq(
        MonorepoReleasePlugin.autoImport.releaseIOMonorepoPublishChecks := true
      )
    )(
      firstSettings = projectBase =>
        Seq(
          publish / skip                           := false,
          publishTo                                := Some(
            Resolver.file("local", new File(projectBase.getParentFile, "repo"))
          ),
          ReleaseSharedKeys.releaseIOPublishAction := { /* no-op publish */ }
        ),
      secondSettings = projectBase =>
        Seq(
          publish / skip                           := false,
          publishTo                                := None,
          ReleaseSharedKeys.releaseIOPublishAction :=
            sbt.IO.write(new File(projectBase.getParentFile, "api-published.txt"), "published")
        )
    ).use { fixture =>
      val ctx       = fixture.context(Seq("core"))
      val core      = fixture.projectInfo("core")
      val api       = fixture.projectInfo("api")
      val apiMarker = new File(fixture.dir, "api-published.txt")

      for {
        validated   <- MonorepoPublishSteps.publishArtifacts.validate(ctx, core)
        driftedCtx   = validated.withProjects(Seq(api))
        executeGate <- MonorepoPublishSteps.shouldRunPublishHooksAtExecute(driftedCtx, api)
        _            = assertEquals(executeGate, false)
        result      <- MonorepoPublishSteps.publishArtifacts.execute(driftedCtx, api)
        published   <- IO.blocking(apiMarker.exists())
        _            = assert(!published)
        _            = assertEquals(result.publishExecutedKeys, Some(Set.empty[String]))
      } yield ()
    }
  }

  test(
    "publishArtifacts: validated publish / skip=false may still become skipped at execute time"
  ) {
    singleProjectFixtureResource(
      "monorepo-publish-project-skip-late-true",
      rootSettings = Seq(
        MonorepoReleasePlugin.autoImport.releaseIOMonorepoPublishChecks := true
      )
    ) { projectBase =>
      Seq(
        publish / skip                           := false,
        publishTo                                := Some(
          Resolver.file("local", new File(projectBase.getParentFile, "repo"))
        ),
        ReleaseSharedKeys.releaseIOPublishAction := {
          throw new RuntimeException("publish action should not run")
        }
      )
    }.use { fixture =>
      val ctx     = fixture.context(Seq("core"))
      val project = fixture.projectInfo("core")
      val ref     = fixture.refsById("core")

      for {
        validated     <- MonorepoPublishSteps.publishArtifacts.validate(ctx, project)
        validatedScala =
          SbtRuntime.extracted(validated.state).getOpt(ref / scalaVersion).getOrElse("")
        _              = assertEquals(
                           validated.validatedPublishEligibility(ref, validatedScala),
                           Some(true)
                         )
        executeState   = TestSupport.appendSessionSettings(
                           validated.state,
                           Seq(ref / publish / skip := true)
                         )
        result        <- MonorepoPublishSteps.publishArtifacts.execute(
                           validated.withState(executeState),
                           project
                         )
        _              = assert(!result.failed)
        _              = assertEquals(result.publishExecutedKeys, Some(Set.empty[String]))
      } yield ()
    }
  }

  test("publishArtifacts: checks-disabled validation does not freeze publish / skip") {
    singleProjectFixtureResource(
      "monorepo-publish-project-skip-checks-disabled",
      rootSettings = Seq(
        MonorepoReleasePlugin.autoImport.releaseIOMonorepoPublishChecks := false
      )
    ) { projectBase =>
      val marker = new File(projectBase.getParentFile, "published.txt")

      Seq(
        publish / skip                           := true,
        publishTo                                := None,
        ReleaseSharedKeys.releaseIOPublishAction := sbt.IO.write(marker, "published")
      )
    }.use { fixture =>
      val ctx     = fixture.context(Seq("core"))
      val project = fixture.projectInfo("core")
      val ref     = fixture.refsById("core")
      val marker  = new File(fixture.dir, "published.txt")

      for {
        validated   <- MonorepoPublishSteps.publishArtifacts.validate(ctx, project)
        _            = assert(!validated.hasValidatedPublishEligibilitySnapshot)
        executeState = TestSupport.appendSessionSettings(
                         validated.state,
                         Seq(
                           ref / scalaVersion   := TestSupport.alternateScalaVersion,
                           ref / publish / skip := false
                         )
                       )
        executeCtx   = validated.withState(executeState)
        executeKey   = MonorepoPublishSteps.publishGateKey(executeCtx, project)
        result      <- MonorepoPublishSteps.publishArtifacts.execute(executeCtx, project)
        published   <- IO.blocking(marker.exists())
        _            = assert(published)
        _            = assert(result.publishExecutedKeys.exists(_.contains(executeKey)))
      } yield ()
    }
  }

  test("publishArtifacts: direct execute keeps live behavior after Scala version changes") {
    singleProjectFixtureResource("monorepo-publish-direct-scala-change") { projectBase =>
      val marker = new File(projectBase.getParentFile, "published.txt")

      Seq(
        publish / skip                           := false,
        ReleaseSharedKeys.releaseIOPublishAction := sbt.IO.write(marker, "published")
      )
    }.use { fixture =>
      val ctx          = fixture.context(Seq("core"))
      val project      = fixture.projectInfo("core")
      val ref          = fixture.refsById("core")
      val marker       = new File(fixture.dir, "published.txt")
      assert(!ctx.hasValidatedPublishEligibilitySnapshot)
      val executeState = TestSupport.appendSessionSettings(
        ctx.state,
        Seq(
          ref / scalaVersion   := TestSupport.alternateScalaVersion,
          ref / publish / skip := false
        )
      )
      val executeCtx   = ctx.withState(executeState)
      val executeKey   = MonorepoPublishSteps.publishGateKey(executeCtx, project)

      for {
        result    <- MonorepoPublishSteps.publishArtifacts.execute(executeCtx, project)
        published <- IO.blocking(marker.exists())
        _          = assert(published)
        _          = assert(result.publishExecutedKeys.exists(_.contains(executeKey)))
      } yield ()
    }
  }

  test("publishArtifacts.execute - run the configured publish task when publish is enabled") {
    singleProjectFixtureResource("monorepo-publish-run-action") { projectBase =>
      val marker         = new File(projectBase.getParentFile, "published.txt")
      val fallbackMarker = new File(projectBase.getParentFile, "publish-fallback.txt")

      Seq(
        publish / skip                           := false,
        publishTo                                := Some(Resolver.file("local-test", projectBase.getParentFile)),
        publish                                  := {
          sbt.IO.write(fallbackMarker, "fallback")
        },
        ReleaseSharedKeys.releaseIOPublishAction := {
          sbt.IO.write(marker, "published")
        }
      )
    }.use { fixture =>
      val buffered = MonorepoPublishArtifactsSpec.bufferedFixture(fixture)
      val ctx      = buffered.fixture.context(Seq("core"))
      val project  = buffered.fixture.projectInfo("core")
      val warning  = MonorepoPublishArtifactsSpec.publishFallbackWarning("core")

      for {
        _   <- MonorepoPublishSteps.publishArtifacts.execute(ctx, project)
        log <- IO.blocking(buffered.consoleBuffer.toString("UTF-8"))
      } yield {
        assert(new File(fixture.dir, "published.txt").exists())
        assert(!new File(fixture.dir, "publish-fallback.txt").exists())
        assertEquals(TestSupport.warningCount(log, warning), 0)
      }
    }
  }

  test(
    "publishArtifacts.execute - fall back to publish and warn when releaseIOPublishAction is undefined"
  ) {
    MonorepoSpecSupport
      .loadedFixtureResource("monorepo-publish-fallback-action") { dir =>
        val projectBase = new File(dir, "core")
        projectBase.mkdirs()

        Seq(
          MonorepoSpecSupport.monorepoRootProject(dir, projectIds = Seq("core")),
          MonorepoSpecSupport
            .versionedProject(
              "core",
              projectBase,
              settings = Seq(
                publish / skip := false,
                publishTo      := Some(Resolver.file("local-test", projectBase.getParentFile)),
                publish        := {
                  Def
                    .task(())
                    .updateState { (state: State, _: Unit) =>
                      state.put(MonorepoPublishArtifactsSpec.executionStateKey, "publish")
                    }
                    .value
                }
              )
            )
            .enablePlugins(sbt.plugins.JvmPlugin)
        )
      }
      .use { fixture =>
        val buffered = MonorepoPublishArtifactsSpec.bufferedFixture(fixture)
        val ctx      = buffered.fixture.context(Seq("core"))
        val project  = buffered.fixture.projectInfo("core")
        val warning  = MonorepoPublishArtifactsSpec.publishFallbackWarning("core")

        for {
          result <- MonorepoPublishSteps.publishArtifacts.execute(ctx, project)
          log    <- IO.blocking(buffered.consoleBuffer.toString("UTF-8"))
        } yield {
          assertEquals(
            result.state.get(MonorepoPublishArtifactsSpec.executionStateKey),
            Some("publish")
          )
          assertEquals(TestSupport.warningCount(log, warning), 1)
        }
      }
  }

  test(
    "publishArtifacts.execute - honor ThisBuild releaseIOPublishAction without fallback warning"
  ) {
    MonorepoSpecSupport
      .loadedFixtureResource("monorepo-publish-thisbuild-action") { dir =>
        val projectBase     = new File(dir, "core")
        projectBase.mkdirs()
        val rootSettings    = Seq(
          ThisBuild / ReleaseSharedKeys.releaseIOPublishAction := {
            Def
              .task(())
              .updateState { (state: State, _: Unit) =>
                state.put(MonorepoPublishArtifactsSpec.executionStateKey, "thisbuild")
              }
              .value
          }
        )
        val projectSettings = Seq(
          publish / skip := false,
          publishTo      := Some(Resolver.file("local-test", projectBase.getParentFile)),
          publish        := {
            Def
              .task(())
              .updateState { (state: State, _: Unit) =>
                state.put(MonorepoPublishArtifactsSpec.executionStateKey, "fallback")
              }
              .value
          }
        )

        Seq(
          MonorepoSpecSupport.monorepoRootProject(
            dir,
            projectIds = Seq("core"),
            settings = rootSettings
          ),
          MonorepoSpecSupport
            .versionedProject(
              "core",
              projectBase,
              settings = projectSettings
            )
            .enablePlugins(sbt.plugins.JvmPlugin)
        )
      }
      .use { fixture =>
        val buffered = MonorepoPublishArtifactsSpec.bufferedFixture(fixture)
        val ctx      = buffered.fixture.context(Seq("core"))
        val project  = buffered.fixture.projectInfo("core")
        val warning  = MonorepoPublishArtifactsSpec.publishFallbackWarning("core")

        for {
          result <- MonorepoPublishSteps.publishArtifacts.execute(ctx, project)
          log    <- IO.blocking(buffered.consoleBuffer.toString("UTF-8"))
        } yield {
          assertEquals(
            result.state.get(MonorepoPublishArtifactsSpec.executionStateKey),
            Some("thisbuild")
          )
          assertEquals(TestSupport.warningCount(log, warning), 0)
        }
      }
  }

  test("publishArtifacts.execute - run publish with the release version and release metadata") {
    singleProjectFixtureResource("monorepo-publish-release-metadata") { projectBase =>
      val marker = new File(projectBase.getParentFile, "publish-metadata.txt")

      Seq(
        publish / skip                                       := false,
        publishTo                                            := Some(Resolver.file("local-test", projectBase.getParentFile)),
        version                                              := "0.1.0-SNAPSHOT",
        packageOptions                                       := Seq.empty,
        ReleaseManifestMetadata.releaseIOInternalReleaseHash := None,
        ReleaseManifestMetadata.releaseIOInternalReleaseTag  := None,
        packageOptions ++= ReleaseManifestMetadata
          .releaseManifestPackageOptions(
            ReleaseManifestMetadata.releaseIOInternalReleaseHash.value,
            ReleaseManifestMetadata.releaseIOInternalReleaseTag.value
          ),
        ReleaseSharedKeys.releaseIOPublishAction             := {
          def manifestEntries(options: Seq[PackageOption]): Map[String, String] =
            options.flatMap {
              case product: Product if product.productPrefix == "ManifestAttributes" =>
                product.productElement(0) match {
                  case entries: Seq[?] @unchecked =>
                    entries.collect { case (name, value: String) =>
                      name.toString -> value
                    }
                  case _                          => Seq.empty
                }
              case _                                                                 => Seq.empty
            }.toMap

          val entries = manifestEntries(packageOptions.value)
          sbt.IO.write(
            marker,
            Seq(
              s"version=${version.value}",
              s"hash=${entries.getOrElse("Vcs-Release-Hash", "")}",
              s"tag=${entries.getOrElse("Vcs-Release-Tag", "")}"
            ).mkString("", "\n", "\n")
          )
        }
      )
    }.use { fixture =>
      val coreRef     = fixture.refsById("core")
      // Mirror what the real release pipeline installs by the time
      // publishArtifacts.execute runs: per-project release version (from
      // set-release-version), release hash (from commit-release-versions),
      // and release tag (from tag-releases-per-project) — all installed via
      // appendSessionSettings so they live in session.rawAppend.
      val seededState = TestSupport.appendSessionSettings(
        fixture.state,
        Seq(coreRef / version := "1.0.0") ++
          ReleaseManifestMetadata
            .releaseManifestHashSettings(Seq(coreRef), "abc123") ++
          ReleaseManifestMetadata
            .releaseManifestTagSettings(coreRef, "core/v1.0.0")
      )
      val project     = fixture.projectInfo(
        "core",
        versions = Some("1.0.0" -> "1.1.0-SNAPSHOT"),
        tagName = Some("core/v1.0.0")
      )
      val ctx         = MonorepoContext(state = seededState, projects = Seq(project))

      MonorepoPublishSteps.publishArtifacts.execute(ctx, project).flatMap { _ =>
        IO.blocking {
          val lines = sbt.IO.readLines(new File(fixture.dir, "publish-metadata.txt"))
          assertEquals(lines, List("version=1.0.0", "hash=abc123", "tag=core/v1.0.0"))
        }
      }
    }
  }

  test("publishArtifacts.validate - pass when publishTo is set and publish not skipped") {
    singleProjectFixtureResource(
      "monorepo-publish-validate-pass",
      rootSettings = Seq(
        MonorepoReleasePlugin.autoImport.releaseIOMonorepoPublishChecks := true
      )
    ) { projectBase =>
      Seq(
        publish / skip := false,
        publishTo      := Some(Resolver.file("local-test", projectBase.getParentFile))
      )
    }.use { fixture =>
      val ctx     = fixture.context(Seq("core"))
      val project = fixture.projectInfo("core")

      MonorepoPublishSteps.publishArtifacts.validate(ctx, project)
    }
  }
}

private object MonorepoPublishArtifactsSpec {
  val executionStateKey: AttributeKey[String] =
    AttributeKey[String]("monorepoPublishArtifactsSpecExecution")

  final case class BufferedFixture(
      fixture: MonorepoSpecSupport.LoadedFixture,
      consoleBuffer: ByteArrayOutputStream
  )

  def bufferedFixture(fixture: MonorepoSpecSupport.LoadedFixture): BufferedFixture = {
    val buffered = TestSupport.bufferedState(fixture.dir)
    val state    = sbt.TestBuildState(
      baseState = buffered.state,
      baseDir = fixture.dir,
      projects = fixture.projects,
      currentProjectId = Some("root")
    )
    val refsById =
      SbtRuntime.extracted(state).structure.allProjectRefs.map(ref => ref.project -> ref).toMap

    BufferedFixture(
      fixture = MonorepoSpecSupport.LoadedFixture(
        dir = fixture.dir,
        state = state,
        projects = fixture.projects,
        refsById = refsById
      ),
      consoleBuffer = buffered.consoleBuffer
    )
  }

  def publishFallbackWarning(projectName: String): String =
    s"${ReleaseLogPrefixes.Monorepo} $projectName: " +
      s"${ReleaseSharedKeys.releaseIOPublishAction.key.label} is undefined; " +
      s"falling back to ${publish.key.label}"

  def unvalidatedIterationWarning(projectName: String): String =
    s"${ReleaseLogPrefixes.Monorepo} Skipping publish for $projectName: " +
      "the current project/Scala iteration was not covered by checks-enabled publish validation"

  def publishSkipScalaDriftMessage(
      projectName: String,
      before: String,
      after: String
  ): String =
    s"publish-artifacts: publish / skip changed scalaVersion for $projectName from '$before' " +
      s"to '$after'; checks-enabled publish validation requires a stable project/Scala iteration"

  def publishPreparationScalaDriftMessage(
      projectName: String,
      before: String,
      after: String
  ): String =
    s"publish-artifacts: publish preparation changed scalaVersion for $projectName from " +
      s"'$before' to '$after'; checks-enabled publish validation requires a stable " +
      "project/Scala iteration"
}
