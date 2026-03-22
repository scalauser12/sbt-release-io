package io.release.steps

import cats.effect.{IO, Resource}
import io.release.internal.SbtCompat
import io.release.{ReleaseContext, ReleaseIO, ReleaseIOCompat, TestSupport}
import munit.CatsEffectSuite
import sbt.{Project, Setting, *}

import java.io.File

class PublishStepsSpec extends CatsEffectSuite {
  private val fixturePrefix = "publish-steps-spec"

  test(
    "publishArtifacts.execute - fail with an attributed cause when the publish task reports FailureCommand"
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
              s"publish-artifacts: sbt task '${ReleaseIO.releaseIOPublishArtifactsAction.key.label}'"
            )
          )
        )
      }
    }
  }

  test(
    "runTests.execute - fail with an attributed cause when the test task reports FailureCommand"
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

  test("failOnSbtTaskFailure - strip FailureCommand and retain the clean failure cause") {
    TestSupport.dummyContextResource(s"$fixturePrefix-clean").use { ctx =>
      IO {
        val message  =
          "run-clean: clean action reported failure via FailureCommand"
        val newState = ctx.state.copy(remainingCommands = SbtCompat.FailureCommand :: Nil)
        val result   = PublishSteps.failOnSbtTaskFailure(ctx, newState, message)

        assert(result.failed)
        assertEquals(result.state.remainingCommands, Nil)
        assertEquals(result.failureCause.map(_.getMessage), Some(message))
      }
    }
  }

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
