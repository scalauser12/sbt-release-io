package io.release.steps

import io.release.{ReleaseIO, ReleaseIOCompat}
import sbt.{Def, Setting, State, *}

import java.io.File

private[steps] object CoreStepTestCompat {

  def failureCommandPublishTaskSetting(marker: File): Setting[?] =
    ReleaseIO.releaseIOPublishArtifactsAction := Def
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
}
