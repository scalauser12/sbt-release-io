package io.release.monorepo.steps

import io.release.ReleaseIOCompat
import sbt.{Setting, *}

import java.io.File

// Source-split because sbt 1 and sbt 2 expose different test task result types and caching needs.
private[monorepo] object MonorepoStepTestCompat {

  def successfulTestTaskSetting(marker: File): Setting[?] =
    Test / ReleaseIOCompat.testKey := {
      sbt.IO.write(marker, "ran")
    }

  def failureCommandTestTaskSetting(marker: File): Setting[?] =
    Test / ReleaseIOCompat.testKey := Def
      .task {
        sbt.IO.write(marker, "ran")
      }
      .updateState { (state: State, _: Unit) =>
        state.copy(
          remainingCommands =
            _root_.io.release.internal.SbtCompat.FailureCommand :: state.remainingCommands
        )
      }
      .value

  def successfulCleanTaskSetting(marker: File): Setting[?] =
    Global / Keys.clean := {
      sbt.IO.write(marker, "ran")
    }

  def failureCommandCleanTaskSetting(marker: File): Setting[?] =
    Global / Keys.clean := Def
      .task {
        sbt.IO.write(marker, "ran")
      }
      .updateState { (state: State, _: Unit) =>
        state.copy(
          remainingCommands =
            _root_.io.release.internal.SbtCompat.FailureCommand :: state.remainingCommands
        )
      }
      .value
}
