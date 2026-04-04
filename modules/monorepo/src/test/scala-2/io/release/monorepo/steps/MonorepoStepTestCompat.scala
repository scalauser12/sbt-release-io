package io.release.monorepo.steps

import _root_.io.release.internal.SbtCompat
import io.release.ReleaseIO
import io.release.ReleaseIOCompat
import sbt.*
import sbt.Keys.*
import sbt.Setting

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
          remainingCommands = SbtCompat.FailureCommand :: state.remainingCommands
        )
      }
      .value

  def failureCommandCleanTaskSetting(marker: File): Setting[?] =
    Global / Keys.clean := Def
      .task {
        sbt.IO.write(marker, "ran")
      }
      .updateState { (state: State, _: Unit) =>
        state.copy(
          remainingCommands = SbtCompat.FailureCommand :: state.remainingCommands
        )
      }
      .value

  def failureCommandVersionTaskSetting(project: ProjectRef, marker: File): Setting[?] =
    project / ReleaseIO.releaseIOVersioningReleaseVersion := {
      val _ = Def
        .task(())
        .updateState { (state: State, _: Unit) =>
          state.copy(
            remainingCommands = SbtCompat.FailureCommand :: state.remainingCommands
          )
        }
        .value
      sbt.IO.write(marker, "ran")
      (currentVersion: String) => currentVersion.stripSuffix("-SNAPSHOT")
    }

  def failureCommandNextVersionTaskSetting(project: ProjectRef, marker: File): Setting[?] =
    project / ReleaseIO.releaseIOVersioningNextVersion := {
      val _ = Def
        .task(())
        .updateState { (state: State, _: Unit) =>
          state.copy(
            remainingCommands = SbtCompat.FailureCommand :: state.remainingCommands
          )
        }
        .value
      sbt.IO.write(marker, "ran")
      (_: String) => "0.2.0-SNAPSHOT"
    }

  def stateMutationNextVersionTaskSetting(
      project: ProjectRef,
      key: AttributeKey[String],
      value: String
  ): Setting[?] =
    project / ReleaseIO.releaseIOVersioningNextVersion := {
      val _ = Def
        .task(())
        .updateState { (state: State, _: Unit) =>
          state.put(key, value)
        }
        .value
      (_: String) => "0.2.0-SNAPSHOT"
    }

  def throwingPublishSkipSetting: Setting[?] =
    publish / skip := { throw new RuntimeException("publish/skip eval error"); false }
}
