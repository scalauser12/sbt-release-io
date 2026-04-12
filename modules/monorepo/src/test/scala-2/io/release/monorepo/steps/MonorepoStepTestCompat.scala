package io.release.monorepo.internal.steps

import _root_.io.release.runtime.sbt.SbtCompat
import io.release.ReleaseIOCompat
import io.release.ReleaseSharedPlugin
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

  def failureCommandSnapshotDependenciesTaskSetting(
      marker: File,
      dependencies: Seq[ModuleID] = Seq.empty[ModuleID]
  ): Setting[?] =
    ReleaseSharedPlugin.autoImport.releaseIODiagnosticsSnapshotDependencies := Def
      .task {
        sbt.IO.write(marker, "ran")
        dependencies
      }
      .updateState { (state: State, _: Seq[ModuleID]) =>
        state.copy(
          remainingCommands = SbtCompat.FailureCommand :: state.remainingCommands
        )
      }
      .value

  def managedClasspathSetting(marker: File): Setting[?] =
    Test / Keys.managedClasspath := {
      sbt.IO.write(marker, "ran")
      Nil
    }

  def failureCommandVersionTaskSetting(project: ProjectRef, marker: File): Setting[?] =
    project / ReleaseSharedPlugin.autoImport.releaseIOVersioningReleaseVersion := {
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
    project / ReleaseSharedPlugin.autoImport.releaseIOVersioningNextVersion := {
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
    project / ReleaseSharedPlugin.autoImport.releaseIOVersioningNextVersion := {
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
