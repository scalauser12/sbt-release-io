package io.release.monorepo

import cats.effect.IO
import io.release.TestSupport
import io.release.monorepo.internal.steps.MonorepoPublishSteps
import io.release.runtime.sbt.SbtRuntime
import munit.CatsEffectSuite
import sbt.Keys.*
import sbt.{internal as _, *}

import java.io.File

/** Regression coverage for [[MonorepoPublishSteps.publishGateKey]]. The cache key has to
  * distinguish cross-build iterations so the frozen publish-skip decision is recomputed
  * each iteration. Scoping the `scalaVersion` lookup to `project.ref` matters because
  * cross-build only switches per-project (not the unscoped `Keys.scalaVersion`, which
  * resolves at sbt's `currentRef` and stays constant across iterations).
  */
class MonorepoPublishGateKeySpec extends CatsEffectSuite {

  test(
    "publishGateKey reflects the project-scoped scalaVersion (so each cross-iteration's frozen decision has its own cache slot)"
  ) {
    val coreScalaA = TestSupport.CurrentScalaVersion
    val coreScalaB = TestSupport.alternateScalaVersion
    val coreScalaC = "9.9.9"

    MonorepoSpecSupport
      .loadedContextResource("monorepo-publish-gate-key", Seq("core")) { dir =>
        val coreBase = new File(dir, "core")
        coreBase.mkdirs()
        Seq(
          Project("root", dir)
            .aggregate(LocalProject("core"))
            .settings(scalaVersion := coreScalaA),
          Project("core", coreBase).settings(
            scalaVersion           := coreScalaA
          )
        )
      }
      .use { ctx =>
        val coreProject    = MonorepoSpecSupport.projectNamed(ctx.projects, "core")
        val coreScope      = Scope(Select(coreProject.ref), Zero, Zero, Zero)
        val foreignCoreRef = ProjectRef(
          new File(coreProject.baseDir.getParentFile, "foreign-build").toURI,
          coreProject.ref.project
        )

        def installCoreScala(version: String): IO[MonorepoContext] =
          IO.blocking {
            val extracted                                             = SbtRuntime.extracted(ctx.state)
            import extracted.*
            implicit val showKey: sbt.util.Show[sbt.Def.ScopedKey[?]] = extracted.showKey
            val ss: Setting[?]                                        =
              coreScope / scalaVersion := version
            val newSession   = session.appendRaw(Seq(ss))
            val newStructure =
              _root_.io.release.LoadCompat.reapply(newSession.mergeSettings, structure)
            val newState     =
              Project.setProject(newSession, newStructure, ctx.state)
            ctx.withState(newState)
          }

        for {
          ctxA            <- installCoreScala(coreScalaA)
          ctxB            <- installCoreScala(coreScalaB)
          ctxC            <- installCoreScala(coreScalaC)
          keyA             = MonorepoPublishSteps.publishGateKey(ctxA, coreProject)
          keyB             = MonorepoPublishSteps.publishGateKey(ctxB, coreProject)
          eligibilityCtx   = ctxA
                               .recordValidatedPublishEligibility(
                                 coreProject.ref,
                                 coreScalaA,
                                 eligible = false
                               )
                               .recordValidatedPublishEligibility(
                                 coreProject.ref,
                                 coreScalaA,
                                 eligible = true
                               )
                               .withState(ctxB.state)
                               .recordValidatedPublishEligibility(
                                 coreProject.ref,
                                 coreScalaB,
                                 eligible = true
                               )
                               .recordValidatedPublishEligibility(
                                 foreignCoreRef,
                                 coreScalaA,
                                 eligible = true
                               )
          missingCtx       = eligibilityCtx.withState(ctxC.state)
          missingGate     <- MonorepoPublishSteps.shouldRunPublishHooksAtExecute(
                               missingCtx,
                               coreProject
                             )
          iterationA       = MonorepoContext.PublishIteration(coreProject.ref, coreScalaA)
          iterationB       = MonorepoContext.PublishIteration(coreProject.ref, coreScalaB)
          iterationC       = MonorepoContext.PublishIteration(coreProject.ref, coreScalaC)
          restoredEntry    = MonorepoContext.PublishIteration(
                               coreProject.ref,
                               "restored-entry"
                             )
          restoredCtx      = ctxA.beginPublishExecutionBatch
                               .recordPublishAttempt(iterationA)
                               .recordPublishSucceeded(
                                 attemptIteration = iterationA,
                                 actionIteration = iterationB,
                                 hookSource = iterationB,
                                 taskReturnedIteration = iterationB
                               )
                               .withState(ctxA.state)
          laterSkippedCtx  = restoredCtx
                               .recordPublishAttempt(iterationC)
                               .withState(ctxA.state)
          convergedSkipCtx = laterSkippedCtx
                               .recordPublishAttempt(iterationB)
                               .withState(ctxB.state)
          restoredOutcome  = restoredCtx.afterPublishOutcome(iterationA)
          returnedOutcome  = restoredCtx.afterPublishOutcome(iterationB)
          retainedOutcome  = laterSkippedCtx.afterPublishOutcome(iterationA)
          fallbackOutcome  = laterSkippedCtx.afterPublishOutcome(restoredEntry)
          skippedOutcome   = convergedSkipCtx.afterPublishOutcome(iterationB)
        } yield {
          assert(keyA.contains(coreScalaA), s"key A should encode core's scalaVersion: $keyA")
          assert(keyB.contains(coreScalaB), s"key B should encode core's scalaVersion: $keyB")
          assertNotEquals(
            keyA,
            keyB,
            "publishGateKey must yield distinct strings when project.ref / scalaVersion differs " +
              "across cross-iterations — without project-scoping the unscoped scalaVersion at " +
              "currentRef (typically root) collapses both iterations onto one cache slot"
          )
          assert(!ctxA.hasValidatedPublishEligibilitySnapshot)
          assert(eligibilityCtx.hasValidatedPublishEligibilitySnapshot)
          assertEquals(
            eligibilityCtx.validatedPublishEligibility(coreProject.ref, coreScalaA),
            Some(false)
          )
          assertEquals(
            eligibilityCtx.validatedPublishEligibility(coreProject.ref, coreScalaB),
            Some(true)
          )
          assertEquals(
            eligibilityCtx.validatedPublishEligibility(coreProject.ref, coreScalaC),
            None
          )
          assertEquals(
            eligibilityCtx.validatedPublishEligibility(foreignCoreRef, coreScalaA),
            Some(true),
            "same-named projects in different builds must have independent eligibility"
          )
          assertEquals(missingGate, false)
          assertEquals(
            restoredOutcome,
            MonorepoContext.AfterPublishOutcome(iterationA, succeeded = true),
            "a successful A attempt must retain A's frozen key when the task restores live A"
          )
          assertEquals(
            MonorepoPublishSteps.afterPublishGateKey(restoredCtx, coreProject),
            keyA,
            "the frozen gate must use the successful attempt identity"
          )
          assert(MonorepoPublishSteps.didPublishForAfterHook(restoredCtx, coreProject))
          assertEquals(
            returnedOutcome,
            MonorepoContext.AfterPublishOutcome(iterationA, succeeded = true),
            "a returned post-skip identity must resolve back to its successful attempt"
          )
          assertEquals(
            retainedOutcome,
            MonorepoContext.AfterPublishOutcome(iterationA, succeeded = true),
            "the successful attempt must retain its frozen key after a later skip"
          )
          assertEquals(
            fallbackOutcome,
            MonorepoContext.AfterPublishOutcome(restoredEntry, succeeded = false),
            "an unknown same-project identity must not borrow the project's last success"
          )
          assertEquals(
            skippedOutcome,
            MonorepoContext.AfterPublishOutcome(iterationB, succeeded = false),
            "a skipped cross-build iteration must remain unsuccessful even when its identity " +
              "matches another attempt's successful hook source"
          )
          assertEquals(
            MonorepoPublishSteps.afterPublishGateKey(convergedSkipCtx, coreProject),
            keyB,
            "converging attempt and source identities should retain one frozen key"
          )
          assert(
            !MonorepoPublishSteps.didPublishForAfterHook(convergedSkipCtx, coreProject),
            "the converged skipped attempt must not run afterPublish a second time"
          )
        }
      }
  }

  test("after-publish resolves exact action, hook, and task-returned aliases") {
    probeContextResource("monorepo-after-publish-exact-aliases").use { ctx =>
      IO {
        val ref          = ctx.currentProjects.head.ref
        val attempt      = MonorepoContext.PublishIteration(ref, "attempt")
        val action       = MonorepoContext.PublishIteration(ref, "action")
        val hookSource   = MonorepoContext.PublishIteration(ref, "hook-source")
        val taskReturned = MonorepoContext.PublishIteration(ref, "task-returned")
        val successful   = ctx.beginPublishExecutionBatch
          .recordPublishAttempt(attempt)
          .recordPublishSucceeded(
            attemptIteration = attempt,
            actionIteration = action,
            hookSource = hookSource,
            taskReturnedIteration = taskReturned
          )
        val expected     = MonorepoContext.AfterPublishOutcome(attempt, succeeded = true)

        assertEquals(successful.afterPublishOutcome(attempt), expected)
        assertEquals(successful.afterPublishOutcome(action), expected)
        assertEquals(successful.afterPublishOutcome(hookSource), expected)
        assertEquals(successful.afterPublishOutcome(taskReturned), expected)
      }
    }
  }

  test("after-publish rejects an unknown identity from a successful project") {
    probeContextResource("monorepo-after-publish-unknown-same-project").use { ctx =>
      IO {
        val ref        = ctx.currentProjects.head.ref
        val attempt    = MonorepoContext.PublishIteration(ref, "attempt")
        val unknown    = MonorepoContext.PublishIteration(ref, "unknown")
        val successful = ctx.beginPublishExecutionBatch
          .recordPublishAttempt(attempt)
          .recordPublishSucceeded(
            attemptIteration = attempt,
            actionIteration = attempt,
            hookSource = attempt,
            taskReturnedIteration = attempt
          )
        val expected   = MonorepoContext.AfterPublishOutcome(unknown, succeeded = false)

        assertEquals(successful.afterPublishOutcome(unknown), expected)
        assertEquals(successful.currentPublishExecutionOutcome(unknown), Some(expected))
      }
    }
  }

  test("an attempted publish failure takes precedence over a successful alias") {
    probeContextResource("monorepo-after-publish-attempt-precedence").use { ctx =>
      IO {
        val ref               = ctx.currentProjects.head.ref
        val successfulAttempt = MonorepoContext.PublishIteration(ref, "successful-attempt")
        val failedAlias       = MonorepoContext.PublishIteration(ref, "failed-alias")
        val execution         = ctx.beginPublishExecutionBatch
          .recordPublishAttempt(successfulAttempt)
          .recordPublishSucceeded(
            attemptIteration = successfulAttempt,
            actionIteration = successfulAttempt,
            hookSource = failedAlias,
            taskReturnedIteration = failedAlias
          )
          .recordPublishAttempt(failedAlias)

        assertEquals(
          execution.afterPublishOutcome(successfulAttempt),
          MonorepoContext.AfterPublishOutcome(successfulAttempt, succeeded = true)
        )
        assertEquals(
          execution.afterPublishOutcome(failedAlias),
          MonorepoContext.AfterPublishOutcome(failedAlias, succeeded = false)
        )
      }
    }
  }

  test("an alias shared by multiple successful attempts fails closed") {
    probeContextResource("monorepo-after-publish-ambiguous-alias").use { ctx =>
      IO {
        val ref       = ctx.currentProjects.head.ref
        val attemptA  = MonorepoContext.PublishIteration(ref, "attempt-a")
        val attemptB  = MonorepoContext.PublishIteration(ref, "attempt-b")
        val shared    = MonorepoContext.PublishIteration(ref, "shared-returned-alias")
        val execution = ctx.beginPublishExecutionBatch
          .recordPublishAttempt(attemptA)
          .recordPublishSucceeded(
            attemptIteration = attemptA,
            actionIteration = attemptA,
            hookSource = attemptA,
            taskReturnedIteration = shared
          )
          .recordPublishAttempt(attemptB)
          .recordPublishSucceeded(
            attemptIteration = attemptB,
            actionIteration = attemptB,
            hookSource = attemptB,
            taskReturnedIteration = shared
          )

        assertEquals(
          execution.afterPublishOutcome(attemptA),
          MonorepoContext.AfterPublishOutcome(attemptA, succeeded = true)
        )
        assertEquals(
          execution.afterPublishOutcome(attemptB),
          MonorepoContext.AfterPublishOutcome(attemptB, succeeded = true)
        )
        assertEquals(
          execution.afterPublishOutcome(shared),
          MonorepoContext.AfterPublishOutcome(shared, succeeded = false)
        )
      }
    }
  }

  test("after-publish validation reuses the current successful attempt gate") {
    probeContextResource("monorepo-after-publish-current-outcome").use { ctx =>
      val project    = ctx.currentProjects.head
      val ref        = project.ref
      val iterationA = MonorepoContext.PublishIteration(ref, TestSupport.CurrentScalaVersion)
      val iterationB = MonorepoContext.PublishIteration(ref, TestSupport.alternateScalaVersion)
      val stateB     = TestSupport.appendSessionSettings(
        ctx.state,
        Seq(ref / scalaVersion := iterationB.scalaVersion)
      )

      def validationProbe(publishSkipped: Boolean) =
        MonorepoContext.PublishValidationProbe(
          input = iterationA,
          entry = iterationA,
          postSkip = iterationA,
          publishSkipped = publishSkipped,
          pendingTargetState = None,
          targetValidated = true
        )

      def successfulAttempt(base: MonorepoContext) =
        base.beginPublishExecutionBatch
          .recordPublishAttempt(iterationA)
          .recordPublishSucceeded(
            attemptIteration = iterationA,
            actionIteration = iterationA,
            hookSource = iterationA,
            taskReturnedIteration = iterationB
          )
          .withState(stateB)

      val eligibleCtx = successfulAttempt(
        ctx.recordPublishValidationProbe(validationProbe(publishSkipped = false))
      )
      val skippedCtx  = successfulAttempt(
        ctx.recordPublishValidationProbe(validationProbe(publishSkipped = true))
      )
      val probeLess   = successfulAttempt(ctx)
      val nextBatch   = eligibleCtx.beginPublishValidationBatch()

      for {
        eligibleGate <- MonorepoPublishSteps.afterPublishGateValidation(eligibleCtx, project)
        skippedGate  <- MonorepoPublishSteps.afterPublishGateValidation(skippedCtx, project)
        fallbackGate <- MonorepoPublishSteps.afterPublishGateValidation(probeLess, project)
      } yield {
        val expectedOutcome =
          Some(MonorepoContext.AfterPublishOutcome(iterationA, succeeded = true))

        assertEquals(eligibleCtx.currentPublishExecutionOutcome(iterationB), expectedOutcome)
        assertEquals(eligibleCtx.validatedPublishGateDecision(iterationA), Some(true))
        assertEquals(skippedCtx.validatedPublishGateDecision(iterationA), Some(false))
        assertEquals(probeLess.validatedPublishGateDecision(iterationA), None)
        assertEquals(eligibleGate.key, iterationA.gateKey)
        assertEquals(eligibleGate.decision, true)
        assertEquals(skippedGate.key, iterationA.gateKey)
        assertEquals(skippedGate.decision, false)
        assertEquals(fallbackGate.key, iterationA.gateKey)
        assertEquals(fallbackGate.decision, true)
        assertEquals(nextBatch.currentPublishExecutionOutcome(iterationB), None)
      }
    }
  }

  test("publish hook gates map a pre-overlay attempt to its validation entry") {
    probeContextResource("monorepo-publish-gate-input-entry").use { ctx =>
      val project = ctx.currentProjects.head
      val input   = MonorepoContext.PublishIteration(
        project.ref,
        TestSupport.CurrentScalaVersion
      )
      val entry   = MonorepoContext.PublishIteration(
        project.ref,
        TestSupport.alternateScalaVersion
      )

      def validationProbe(publishSkipped: Boolean) =
        MonorepoContext.PublishValidationProbe(
          input = input,
          entry = entry,
          postSkip = entry,
          publishSkipped = publishSkipped,
          pendingTargetState = None,
          targetValidated = true
        )

      val inputState = TestSupport.appendSessionSettings(
        ctx.state,
        Seq(project.ref / scalaVersion := input.scalaVersion)
      )

      def successfulAttempt(base: MonorepoContext) =
        base
          .withState(inputState)
          .beginPublishExecutionBatch
          .recordPublishAttempt(input)
          .recordPublishSucceeded(input)

      val eligible   = successfulAttempt(
        ctx.recordPublishValidationProbe(validationProbe(publishSkipped = false))
      )
      val skipped    = successfulAttempt(
        ctx.recordPublishValidationProbe(validationProbe(publishSkipped = true))
      )
      val unknown    = MonorepoContext.PublishIteration(project.ref, "unrelated-scala")
      val unknownCtx = eligible.withState(
        TestSupport.appendSessionSettings(
          eligible.state,
          Seq(project.ref / scalaVersion := unknown.scalaVersion)
        )
      )

      for {
        eligibleGate <- MonorepoPublishSteps.afterPublishGateValidation(eligible, project)
        skippedGate  <- MonorepoPublishSteps.afterPublishGateValidation(skipped, project)
      } yield {
        assertEquals(MonorepoPublishSteps.publishGateKey(eligible, project), entry.gateKey)
        assertEquals(MonorepoPublishSteps.afterPublishGateKey(eligible, project), entry.gateKey)
        assertEquals(eligibleGate.key, entry.gateKey)
        assertEquals(eligibleGate.decision, true)
        assertEquals(skippedGate.key, entry.gateKey)
        assertEquals(skippedGate.decision, false)
        assertEquals(
          MonorepoPublishSteps.publishGateKey(ctx.withState(inputState), project),
          input.gateKey
        )
        assertEquals(
          MonorepoPublishSteps.publishGateKey(unknownCtx, project),
          unknown.gateKey,
          "only an exact validation-probe input should remap to the overlay entry"
        )
      }
    }
  }

  test("publish validation probes use an entry index and accept identical collapses") {
    probeContextResource("monorepo-publish-probe-entry-index").use { ctx =>
      IO {
        val ref      = ctx.currentProjects.head.ref
        val inputA   = MonorepoContext.PublishIteration(ref, "input-a")
        val inputB   = MonorepoContext.PublishIteration(ref, "input-b")
        val entry    = MonorepoContext.PublishIteration(ref, "entry")
        val postSkip = MonorepoContext.PublishIteration(ref, "post-skip")
        val probeA   = MonorepoContext.PublishValidationProbe(
          inputA,
          entry,
          postSkip,
          publishSkipped = false,
          pendingTargetState = None,
          targetValidated = true
        )
        val probeB   = probeA.copy(input = inputB)
        val indexed  = ctx.recordPublishValidationProbe(probeA).recordPublishValidationProbe(probeB)

        assertEquals(indexed.publishValidationProbe(inputA), Some(probeA))
        assertEquals(indexed.publishValidationProbe(inputB), Some(probeB))
        assertEquals(indexed.validatedPublishHookSource(entry), Some(postSkip))
      }
    }
  }

  test("publish validation probe collisions fail deterministically") {
    probeContextResource("monorepo-publish-probe-entry-conflict").use { ctx =>
      IO {
        val ref        = ctx.currentProjects.head.ref
        val inputA     = MonorepoContext.PublishIteration(ref, "input-a")
        val inputB     = MonorepoContext.PublishIteration(ref, "input-b")
        val entry      = MonorepoContext.PublishIteration(ref, "entry")
        val postSkipA  = MonorepoContext.PublishIteration(ref, "post-skip-a")
        val postSkipB  = MonorepoContext.PublishIteration(ref, "post-skip-b")
        val eligible   = MonorepoContext.PublishValidationProbe(
          inputA,
          entry,
          postSkipA,
          publishSkipped = false,
          pendingTargetState = None,
          targetValidated = true
        )
        val skipped    = eligible.copy(input = inputB, publishSkipped = true)
        val shifted    = eligible.copy(input = inputB, postSkip = postSkipB)
        val skippedMsg = intercept[IllegalStateException] {
          ctx.recordPublishValidationProbe(eligible).recordPublishValidationProbe(skipped)
        }.getMessage
        val reverseMsg = intercept[IllegalStateException] {
          ctx.recordPublishValidationProbe(skipped).recordPublishValidationProbe(eligible)
        }.getMessage
        val shiftedMsg = intercept[IllegalStateException] {
          ctx.recordPublishValidationProbe(eligible).recordPublishValidationProbe(shifted)
        }.getMessage

        assertEquals(skippedMsg, reverseMsg)
        assert(skippedMsg.contains("publishSkipped=false"))
        assert(skippedMsg.contains("publishSkipped=true"))
        assert(shiftedMsg.contains(postSkipA.gateKey))
        assert(shiftedMsg.contains(postSkipB.gateKey))
      }
    }
  }

  private def probeContextResource(prefix: String) =
    MonorepoSpecSupport.loadedContextResource(prefix, Seq("core")) { dir =>
      val coreBase = new File(dir, "core")
      coreBase.mkdirs()
      Seq(
        Project("root", dir).aggregate(LocalProject("core")),
        Project("core", coreBase)
      )
    }
}
