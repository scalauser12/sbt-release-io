package io.release

import sbt.Def
import sbt.Keys
import sbt.Setting
import sbt.State
import sbt.TaskKey
import sbt.*

import java.io.File

private[release] object ReleaseStepIOCrossBuildCompat {

  def failureCommandTaskSetting(task: TaskKey[Unit], marker: File): Setting[?] =
    task := Def
      .task {
        sbt.IO.append(marker, s"${Keys.scalaVersion.value}\n")
      }
      .updateState { (state: State, _: Unit) =>
        state.copy(
          remainingCommands =
            _root_.io.release.internal.SbtCompat.FailureCommand :: state.remainingCommands
        )
      }
      .value
}
