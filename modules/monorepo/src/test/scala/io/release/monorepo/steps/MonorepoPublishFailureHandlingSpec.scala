package io.release.monorepo.steps

import cats.effect.IO
import io.release.monorepo.MonorepoSpecSupport
import munit.CatsEffectSuite

import java.io.File

class MonorepoPublishFailureHandlingSpec
    extends CatsEffectSuite
    with MonorepoPublishStepsSpecSupport {

  test(
    "runTests via compose - consume FailureCommand per project, keep later projects running, and fail with attribution"
  ) {
    twoProjectFixtureResource("monorepo-publish-run-tests-failure-command")(
      firstSettings = projectBase =>
        Seq(MonorepoStepTestCompat.failureCommandTestTaskSetting(new File(projectBase, "test-ran.txt"))),
      secondSettings = projectBase =>
        Seq(MonorepoStepTestCompat.successfulTestTaskSetting(new File(projectBase, "test-ran.txt")))
    ).use { fixture =>
      val ctx = fixture.context(Seq("core", "api"))

      io.release.monorepo.MonorepoStepIO.compose(Seq(MonorepoPublishSteps.runTests))(ctx).map {
        result =>
          val coreRun = new File(
            MonorepoSpecSupport.projectNamed(result.projects, "core").baseDir,
            "test-ran.txt"
          )
          val apiRun  = new File(
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
      Seq(MonorepoStepTestCompat.failureCommandCleanTaskSetting(new File(projectBase, "clean-ran.txt")))
    }.use { fixture =>
      val ctx = fixture.context(Seq("core"))

      io.release.monorepo.MonorepoStepIO.compose(Seq(MonorepoPublishSteps.runClean))(ctx).map {
        result =>
          val coreRun = new File(
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
        io.release.monorepo.MonorepoReleaseIO.releaseIOMonorepoPublishArtifactsChecks := false
      )
    ) { _ =>
      Seq(MonorepoStepTestCompat.throwingPublishSkipSetting)
    }.use { fixture =>
      val ctx = fixture.context(Seq("core"))

      io.release.monorepo.MonorepoStepIO
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

  test("per-project execution - isolate NonFatal exception and mark project failed") {
    twoProjectFixtureResource("monorepo-publish-exception-isolation")().use { fixture =>
      val ctx = fixture.context(Seq("core", "api"))

      val throwingStep = io.release.monorepo.MonorepoStepIO.PerProject(
        name = "throwing-step",
        execute = (_, project) =>
          IO.raiseError(new RuntimeException(s"${project.name} action blew up"))
      )

      io.release.monorepo.MonorepoStepIO.compose(Seq(throwingStep))(ctx).map { result =>
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

