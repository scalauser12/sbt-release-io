package io.release

import sbt.{Def, Setting, State, TaskKey, *}

import java.io.File
import _root_.io.release.runtime.sbt.SbtCompat

private[release] object ReleaseStepIOCrossBuildCompat {

  def failureCommandTaskSetting(task: TaskKey[Unit], marker: File): Setting[?] =
    task := Def
      .task[Unit] {
        sbt.IO.append(marker, "task-ran\n")
      }
      .updateState { (state: State, _: Unit) =>
        state.copy(
          remainingCommands = SbtCompat.FailureCommand :: state.remainingCommands
        )
      }
      .value
}
