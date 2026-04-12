package io.release

import _root_.io.release.runtime.sbt.SbtCompat
import sbt.*
import sbt.Def
import sbt.Keys
import sbt.complete.DefaultParsers.spaceDelimited
import sbt.InputKey
import sbt.Setting
import sbt.State
import sbt.TaskKey

import java.io.File

private[release] object ReleaseStepIOCrossBuildCompat {

  private def failureCommandInputTaskResult(
      parsedArgs: Seq[String],
      marker: File
  ): Def.Initialize[sbt.Task[String]] =
    Def
      .task {
        val parsed = parsedArgs.mkString(":")
        sbt.IO.write(marker, parsed)
        parsed
      }
      .updateState { (state: State, _: String) =>
        state.copy(
          remainingCommands = SbtCompat.FailureCommand :: state.remainingCommands
        )
      }

  def inputTaskBuildSettings: Seq[Setting[?]] =
    Seq(
      Keys.buildStructure := Keys.state.value
        .get(Keys.stateBuildStructure)
        .getOrElse(sys.error("Missing stateBuildStructure in test build state")),
      Keys.settingsData   := Keys.buildStructure.value.data
    )

  def failureCommandTaskSetting(task: TaskKey[Unit], marker: File): Setting[?] =
    task := Def
      .task {
        sbt.IO.append(marker, "task-ran\n")
      }
      .updateState { (state: State, _: Unit) =>
        state.copy(
          remainingCommands = SbtCompat.FailureCommand :: state.remainingCommands
        )
      }
      .value

  def failureCommandInputTaskSetting(inputTask: InputKey[String], marker: File): Setting[?] =
    inputTask := InputTask
      .createDyn(Def.value((_: State) => spaceDelimited("<arg>"))) {
        Def.task { (parsedArgs: Seq[String]) =>
          failureCommandInputTaskResult(parsedArgs, marker)
        }
      }
      .evaluated
}
