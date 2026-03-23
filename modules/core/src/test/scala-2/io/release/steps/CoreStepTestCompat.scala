package io.release.steps

import io.release.{ReleaseIO, ReleaseIOCompat}
import sbt.Keys.*
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

  def throwingPublishToSetting: Setting[?] =
    publishTo := { throw new RuntimeException("publishTo eval error"); None }

  def throwingPublishSkipSetting: Setting[?] =
    publish / skip := { throw new RuntimeException("publish/skip eval error"); false }

  def throwingSnapshotDepsSetting: Setting[?] =
    ReleaseIO.releaseIOSnapshotDependencies := {
      throw new RuntimeException("snapshot deps eval error")
      Seq.empty[ModuleID]
    }
}
