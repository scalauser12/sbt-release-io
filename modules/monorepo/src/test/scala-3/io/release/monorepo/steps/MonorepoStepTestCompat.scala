package io.release.monorepo.steps

import io.release.ReleaseIOCompat
import sjsonnew.BasicJsonProtocol
import sbt.{Setting, *}
import sbt.protocol.testing.codec.TestResultFormats

import java.io.File

// Source-split because sbt 1 and sbt 2 expose different test task result types and caching needs.
private[steps] object MonorepoStepTestCompat:

  private object TestResultJsonProtocol extends BasicJsonProtocol, TestResultFormats
  import TestResultJsonProtocol.given

  def successfulTestTaskSetting(marker: File): Setting[?] =
    Test / ReleaseIOCompat.testKey := {
      Def.uncached {
        sbt.IO.write(marker, "ran")
        val result: sbt.protocol.testing.TestResult = sbt.protocol.testing.TestResult.Passed
        result
      }
    }

  def failureCommandTestTaskSetting(marker: File): Setting[?] =
    Test / ReleaseIOCompat.testKey := Def
      .task[sbt.protocol.testing.TestResult] {
        sbt.IO.write(marker, "ran")
        val result: sbt.protocol.testing.TestResult = sbt.protocol.testing.TestResult.Passed
        result
      }
      .updateState { (state: State, _: sbt.protocol.testing.TestResult) =>
        state.copy(
          remainingCommands = _root_.io.release.internal.SbtCompat.FailureCommand :: state.remainingCommands
        )
      }
      .value
