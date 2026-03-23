package io.release.steps

import cats.effect.{IO, Resource}
import io.release.internal.{PublishValidation, SbtCompat}
import io.release.{ReleaseContext, ReleaseIO, ReleaseIOCompat, TestSupport}
import munit.CatsEffectSuite
import sbt.Keys.*
import sbt.{Project, Setting, *}

import java.io.File

class PublishStepsSpec extends CatsEffectSuite {
  private val fixturePrefix = "publish-steps-spec"

  // ── publishArtifacts.execute ────────────────────────────────────────

  test(
    "publishArtifacts.execute - fail with an attributed cause when the publish task " +
      "reports FailureCommand"
  ) {
    loadedContextResource(s"$fixturePrefix-publish") { dir =>
      val marker = new File(dir, "publish-ran.txt")
      marker -> Seq(CoreStepTestCompat.failureCommandPublishTaskSetting(marker))
    }.use { case (ctx, marker) =>
      PublishSteps.publishArtifacts.execute(ctx).map { result =>
        assert(result.failed)
        assert(marker.exists())
        assertEquals(result.state.remainingCommands, Nil)
        assert(
          result.failureCause.exists(
            _.getMessage.contains(
              s"publish-artifacts: sbt task " +
                s"'${ReleaseIO.releaseIOPublishArtifactsAction.key.label}'"
            )
          )
        )
      }
    }
  }

  test("publishArtifacts.execute - skip when skipPublish is true") {
    TestSupport.dummyContextResource(s"$fixturePrefix-skip-pub").use { ctx =>
      val skipped = ctx.copy(skipPublish = true)
      PublishSteps.publishArtifacts.execute(skipped).map { result =>
        assert(!result.failed)
      }
    }
  }

  // ── publishArtifacts.validate ───────────────────────────────────────

  test("publishArtifacts.validate - short-circuit when publishArtifactsChecks is false") {
    loadedContextResource(s"$fixturePrefix-val-off") { _ =>
      () -> Seq(ReleaseIO.releaseIOPublishArtifactsChecks := false)
    }.use { case (ctx, _) =>
      PublishSteps.publishArtifacts.validate(ctx)
    }
  }

  test("publishArtifacts.validate - pass when publish/skip is true") {
    loadedContextResource(s"$fixturePrefix-val-skip") { _ =>
      () -> Seq(
        ReleaseIO.releaseIOPublishArtifactsChecks := true,
        publish / skip                            := true
      )
    }.use { case (ctx, _) =>
      PublishSteps.publishArtifacts.validate(ctx)
    }
  }

  test("publishArtifacts.validate - fail when publishTo is missing and checks enabled") {
    loadedContextResource(s"$fixturePrefix-val-missing") { _ =>
      () -> Seq(ReleaseIO.releaseIOPublishArtifactsChecks := true)
    }.use { case (ctx, _) =>
      PublishSteps.publishArtifacts
        .validate(ctx)
        .attempt
        .map { result =>
          assert(result.isLeft)
          val msg = result.left.toOption.get.getMessage
          assert(msg.contains("publishTo not configured"))
        }
    }
  }

  test(
    "publishArtifacts.validate - treat publishTo eval error as missing (fail validation)"
  ) {
    loadedContextResource(s"$fixturePrefix-val-throw-pt") { _ =>
      () -> Seq(
        ReleaseIO.releaseIOPublishArtifactsChecks := true,
        CoreStepTestCompat.throwingPublishToSetting
      )
    }.use { case (ctx, _) =>
      PublishSteps.publishArtifacts
        .validate(ctx)
        .attempt
        .map { result =>
          assert(result.isLeft)
          val msg = result.left.toOption.get.getMessage
          assert(msg.contains("publishTo not configured"))
        }
    }
  }

  test(
    "publishArtifacts.validate - treat publish/skip eval error as not skipped " +
      "(pass when publishTo is set)"
  ) {
    loadedContextResource(s"$fixturePrefix-val-throw-ps") { dir =>
      () -> Seq(
        ReleaseIO.releaseIOPublishArtifactsChecks := true,
        CoreStepTestCompat.throwingPublishSkipSetting,
        publishTo := Some(Resolver.file("local", new File(dir, "repo")))
      )
    }.use { case (ctx, _) =>
      PublishSteps.publishArtifacts.validate(ctx)
    }
  }

  // ── runTests.execute ────────────────────────────────────────────────

  test(
    "runTests.execute - fail with an attributed cause when the test task " +
      "reports FailureCommand"
  ) {
    loadedContextResource(s"$fixturePrefix-tests") { dir =>
      val marker = new File(dir, "test-ran.txt")
      marker -> Seq(CoreStepTestCompat.failureCommandTestTaskSetting(marker))
    }.use { case (ctx, marker) =>
      PublishSteps.runTests.execute(ctx).map { result =>
        assert(result.failed)
        assert(marker.exists())
        assertEquals(result.state.remainingCommands, Nil)
        assert(
          result.failureCause.exists(
            _.getMessage.contains(
              s"run-tests: sbt task 'Test / ${ReleaseIOCompat.testKey.key.label}'"
            )
          )
        )
      }
    }
  }

  test("runTests.execute - skip when skipTests is true") {
    TestSupport.dummyContextResource(s"$fixturePrefix-skip-test").use { ctx =>
      val skipped = ctx.copy(skipTests = true)
      PublishSteps.runTests.execute(skipped).map { result =>
        assert(!result.failed)
      }
    }
  }

  // ── runClean.execute ────────────────────────────────────────────────

  test("runClean.execute - succeed when clean reports no failure") {
    loadedContextResource(s"$fixturePrefix-clean-ok") { _ =>
      () -> Seq.empty[Setting[?]]
    }.use { case (ctx, _) =>
      PublishSteps.runClean.execute(ctx).map { result =>
        assert(!result.failed)
      }
    }
  }

  // ── failOnSbtTaskFailure ────────────────────────────────────────────

  test("failOnSbtTaskFailure - strip FailureCommand and retain the clean failure cause") {
    TestSupport.dummyContextResource(s"$fixturePrefix-clean").use { ctx =>
      IO {
        val message  =
          "run-clean: clean action reported failure via FailureCommand"
        val newState =
          ctx.state.copy(remainingCommands = SbtCompat.FailureCommand :: Nil)
        val result   = PublishSteps.failOnSbtTaskFailure(ctx, newState, message)

        assert(result.failed)
        assertEquals(result.state.remainingCommands, Nil)
        assertEquals(result.failureCause.map(_.getMessage), Some(message))
      }
    }
  }

  // ── helpers ─────────────────────────────────────────────────────────

  private def loadedContextResource[A](
      prefix: String
  )(prepare: File => (A, Seq[Setting[?]])): Resource[IO, (ReleaseContext, A)] =
    TestSupport.tempDirResource(prefix).evalMap { dir =>
      IO.blocking {
        val (value, settings) = prepare(dir)
        val state             = TestSupport.loadedState(
          dir,
          Seq(Project("root", dir).settings(settings*)),
          currentProjectId = Some("root")
        )
        (ReleaseContext(state = state), value)
      }
    }
}
