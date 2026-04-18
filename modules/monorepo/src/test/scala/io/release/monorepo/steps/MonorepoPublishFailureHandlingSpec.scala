package io.release.monorepo.internal.steps

import cats.effect.IO
import io.release.TestAssertions.assertFailure
import io.release.monorepo.MonorepoContext
import io.release.monorepo.MonorepoSpecSupport
import io.release.monorepo.ProjectReleaseInfo
import io.release.monorepo.internal.*
import io.release.monorepo.internal.MonorepoComposer
import io.release.monorepo.internal.steps.*
import io.release.runtime.engine.ProcessStep
import munit.CatsEffectSuite
import sbt.Keys.*

import java.io.File

class MonorepoPublishFailureHandlingSpec
    extends CatsEffectSuite
    with MonorepoPublishStepsSpecSupport {

  test(
    "runTests via compose - consume FailureCommand per project, keep later projects running, and fail with attribution"
  ) {
    twoProjectFixtureResource("monorepo-publish-run-tests-failure-command")(
      firstSettings = projectBase =>
        Seq(
          MonorepoStepTestCompat.failureCommandTestTaskSetting(
            new File(projectBase, "test-ran.txt")
          )
        ),
      secondSettings = projectBase =>
        Seq(MonorepoStepTestCompat.successfulTestTaskSetting(new File(projectBase, "test-ran.txt")))
    ).use { fixture =>
      val ctx = fixture.context(Seq("core", "api"))

      MonorepoComposer.compose(Seq(MonorepoPublishSteps.runTests))(ctx).map { result =>
        val coreRun   = new File(
          MonorepoSpecSupport.projectNamed(result.projects, "core").baseDir,
          "test-ran.txt"
        )
        val apiRun    = new File(
          MonorepoSpecSupport.projectNamed(result.projects, "api").baseDir,
          "test-ran.txt"
        )
        assert(result.failed)
        assert(coreRun.exists())
        assert(apiRun.exists())
        assertEquals(result.state.remainingCommands, Nil)
        val aggregate = requireProjectFailures(result.failureCause)
        assertEquals(aggregate.failures.map(_.projectName), Seq("core"))
        assert(
          aggregate.failures.head.cause.exists(
            _.getMessage.contains("core: sbt task reported failure via FailureCommand")
          )
        )
      }
    }
  }

  test("runClean via compose - consume FailureCommand and fail with project attribution") {
    singleProjectFixtureResource("monorepo-publish-run-clean-failure-command") { projectBase =>
      Seq(
        MonorepoStepTestCompat.failureCommandCleanTaskSetting(
          new File(projectBase, "clean-ran.txt")
        )
      )
    }.use { fixture =>
      val ctx = fixture.context(Seq("core"))

      MonorepoComposer.compose(Seq(MonorepoPublishSteps.runClean))(ctx).map { result =>
        val coreRun   = new File(
          MonorepoSpecSupport.projectNamed(result.projects, "core").baseDir,
          "clean-ran.txt"
        )
        assert(result.failed)
        assert(coreRun.exists())
        assertEquals(result.state.remainingCommands, Nil)
        val aggregate = requireProjectFailures(result.failureCause)
        assertEquals(aggregate.failures.map(_.projectName), Seq("core"))
        assert(
          aggregate.failures.head.cause.exists(
            _.getMessage.contains("core: sbt task reported failure via FailureCommand")
          )
        )
      }
    }
  }

  test("publishArtifacts.execute - wrap NonFatal evaluation error in IllegalStateException") {
    singleProjectFixtureResource(
      "monorepo-publish-eval-error",
      rootSettings = Seq(
        io.release.monorepo.MonorepoReleasePlugin.autoImport.releaseIOMonorepoPublishChecks := false
      )
    ) { _ =>
      Seq(MonorepoStepTestCompat.throwingPublishSkipSetting)
    }.use { fixture =>
      val ctx = fixture.context(Seq("core"))

      MonorepoComposer
        .compose(Seq(MonorepoPublishSteps.publishArtifacts))(ctx)
        .map { result =>
          assert(result.failed)
          val aggregate = requireProjectFailures(result.failureCause)
          assertEquals(aggregate.failures.map(_.projectName), Seq("core"))
          assert(aggregate.failures.head.cause.exists(_.isInstanceOf[IllegalStateException]))
          assert(
            aggregate.failures.head.cause.exists(
              _.getMessage.contains("Failed to evaluate publish / skip for core")
            )
          )
        }
    }
  }

  test("publishArtifacts.execute - fail when publish / skip reports FailureCommand") {
    singleProjectFixtureResource(
      "monorepo-publish-skip-failure-command",
      rootSettings = Seq(
        io.release.monorepo.MonorepoReleasePlugin.autoImport.releaseIOMonorepoPublishChecks := false
      )
    ) { projectBase =>
      Seq(
        MonorepoStepTestCompat.failureCommandPublishSkipSetting(
          new File(projectBase, "publish-skip-ran.txt")
        ),
        io.release.ReleaseSharedKeys.releaseIOPublishAction := {
          throw new RuntimeException("publish action should not run")
        }
      )
    }.use { fixture =>
      val ctx = fixture.context(Seq("core"))

      MonorepoComposer
        .compose(Seq(MonorepoPublishSteps.publishArtifacts))(ctx)
        .map { result =>
          val marker    = new File(
            MonorepoSpecSupport.projectNamed(result.projects, "core").baseDir,
            "publish-skip-ran.txt"
          )
          val aggregate = requireProjectFailures(result.failureCause)

          assert(result.failed)
          assert(marker.exists())
          assertEquals(result.state.remainingCommands, Nil)
          assertEquals(aggregate.failures.map(_.projectName), Seq("core"))
          assert(
            aggregate.failures.head.cause.exists(
              _.getMessage
                .contains("publish-artifacts: sbt task 'skip' reported failure via FailureCommand")
            )
          )
        }
    }
  }

  test("publishArtifacts via compose - fail when publishTo reports FailureCommand") {
    singleProjectFixtureResource("monorepo-publish-target-failure-command") { projectBase =>
      Seq(
        publish / skip                                      := false,
        MonorepoStepTestCompat.failureCommandPublishTargetSetting(
          new File(projectBase, "publish-target-ran.txt")
        ),
        io.release.ReleaseSharedKeys.releaseIOPublishAction := {
          throw new RuntimeException("publish action should not run")
        }
      )
    }.use { fixture =>
      val ctx = fixture.context(Seq("core"))

      assertFailure[IllegalStateException, MonorepoContext](
        MonorepoComposer.compose(Seq(MonorepoPublishSteps.publishArtifacts))(ctx)
      ) { err =>
        val marker = new File(fixture.projectInfo("core").baseDir, "publish-target-ran.txt")

        assert(marker.exists())
        assert(err.getMessage.contains("publish-artifacts: sbt task 'publishTo'"))
        assert(err.getMessage.contains("FailureCommand"))
      }
    }
  }

  test("per-project execution - isolate NonFatal exception and mark project failed") {
    twoProjectFixtureResource("monorepo-publish-exception-isolation")().use { fixture =>
      val ctx = fixture.context(Seq("core", "api"))

      val throwingStep = ProcessStep.PerItem[MonorepoContext, ProjectReleaseInfo](
        name = "throwing-step",
        execute =
          (_, project) => IO.raiseError(new RuntimeException(s"${project.name} action blew up"))
      )

      MonorepoComposer.compose(Seq(throwingStep))(ctx).map { result =>
        assert(result.failed)
        val aggregate = requireProjectFailures(result.failureCause)
        assertEquals(aggregate.failures.map(_.projectName), Seq("core", "api"))
        assert(
          aggregate.failures.head.cause.exists(
            _.getMessage.contains("core action blew up")
          )
        )
        assert(
          aggregate.failures.last.cause.exists(
            _.getMessage.contains("api action blew up")
          )
        )
      }
    }
  }
}
