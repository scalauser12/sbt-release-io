package io.release.internal

import cats.effect.IO
import cats.effect.Ref
import io.release.ReleaseContext
import io.release.ReleasePluginIO.autoImport.*
import io.release.TestAssertions.assertFailure
import io.release.TestSupport
import munit.CatsEffectSuite
import sbt.*
import sbt.Def
import sbt.Project
import sbt.State

import java.io.File

class VersionWorkflowSupportSpec extends CatsEffectSuite {

  private val fixturePrefix       = "version-workflow-support-spec"
  private val startupFlags        = ExecutionFlags(
    useDefaults = false,
    skipTests = false,
    skipPublish = false,
    interactive = false,
    crossBuild = false
  )
  private val versionTaskStateKey =
    sbt.AttributeKey[String]("versionWorkflowSupportStateMarker")

  test("ensureVersionFileExists fails with the caller-supplied message") {
    TestSupport.tempDirResource(fixturePrefix).use { dir =>
      val missing = new File(dir, "missing-version.sbt")

      assertFailure[IllegalStateException, Unit](
        VersionWorkflowSupport.ensureVersionFileExists(missing, "custom missing message")
      )(err => assertEquals(err.getMessage, "custom missing message"))
    }
  }

  test("resolveVersionInputsFromTasks threads task state updates and honors overrides") {
    TestSupport.tempDirResource(fixturePrefix).use { dir =>
      val ctx = promptingContext(
        loadedState(
          dir,
          Seq(
            releaseVersionTaskSetting(
              releaseFn = _.stripSuffix("-SNAPSHOT"),
              stateMarker = "release-task"
            ),
            nextVersionTaskSetting(
              nextFn = _ => "0.2.0-SNAPSHOT",
              stateMarker = "next-task"
            )
          )
        )
      )

      VersionWorkflowSupport
        .resolveVersionInputsFromTasks(
          ctx = ctx,
          currentVersion = "0.1.0-SNAPSHOT",
          releaseVersionTask = releaseIOVersioningReleaseVersion,
          nextVersionTask = releaseIOVersioningNextVersion,
          releaseVersionOverride = Some("1.2.3"),
          nextVersionOverride = Some("1.2.4-SNAPSHOT"),
          logPrefix = ReleaseLogPrefixes.Core,
          releaseLabel = "Release version",
          nextLabel = "Next version",
          allowPrompts = true
        )
        .map { resolved =>
          assertEquals(resolved.context.state.get(versionTaskStateKey), Some("next-task"))
          assertEquals(resolved.releaseVersion, "1.2.3")
          assertEquals(resolved.nextVersion, "1.2.4-SNAPSHOT")
        }
    }
  }

  test("resolveVersionInputsFromTasks suppresses prompting when prompts are disabled") {
    TestSupport.tempDirResource(fixturePrefix).use { dir =>
      val ctx = promptingContext(
        loadedState(
          dir,
          Seq(
            releaseVersionTaskSetting(
              releaseFn = _.stripSuffix("-SNAPSHOT"),
              stateMarker = "release-task"
            ),
            nextVersionTaskSetting(
              nextFn = _ => "0.2.0-SNAPSHOT",
              stateMarker = "next-task"
            )
          )
        )
      )

      Ref.of[IO, Boolean](false).flatMap { beforePromptRan =>
        TestSupport.withInput("") {
          VersionWorkflowSupport
            .resolveVersionInputsFromTasks(
              ctx = ctx,
              currentVersion = "0.1.0-SNAPSHOT",
              releaseVersionTask = releaseIOVersioningReleaseVersion,
              nextVersionTask = releaseIOVersioningNextVersion,
              releaseVersionOverride = None,
              nextVersionOverride = None,
              logPrefix = ReleaseLogPrefixes.Core,
              releaseLabel = "Release version",
              nextLabel = "Next version",
              allowPrompts = false,
              beforeReleasePrompt = beforePromptRan.set(true)
            )
            .flatMap { resolved =>
              beforePromptRan.get.map { ran =>
                assert(!ran)
                assertEquals(resolved.releaseVersion, "0.1.0")
                assertEquals(resolved.nextVersion, "0.2.0-SNAPSHOT")
              }
            }
        }
      }
    }
  }

  private def promptingContext(state: sbt.State): ReleaseContext =
    ReleaseContext(state = state, interactive = true).withExecutionState(
      CoreExecutionState(
        CoreReleasePlan(
          flags = startupFlags.copy(interactive = true),
          releaseVersionOverride = None,
          nextVersionOverride = None,
          decisionDefaults = ReleaseDecisionDefaults.empty
        )
      )
    )

  private def loadedState(dir: File, rootSettings: Seq[sbt.Setting[?]]): sbt.State =
    TestSupport.loadedState(
      dir,
      Seq(Project("root", dir).settings(rootSettings*))
    )

  private def releaseVersionTaskSetting(
      releaseFn: String => String,
      stateMarker: String
  ): sbt.Setting[?] =
    releaseIOVersioningReleaseVersion := Def
      .task {
        releaseFn
      }
      .updateState { (state: State, _: String => String) =>
        state.put(versionTaskStateKey, stateMarker)
      }
      .value

  private def nextVersionTaskSetting(
      nextFn: String => String,
      stateMarker: String
  ): sbt.Setting[?] =
    releaseIOVersioningNextVersion := Def
      .task {
        nextFn
      }
      .updateState { (state: State, _: String => String) =>
        state.put(versionTaskStateKey, stateMarker)
      }
      .value
}
