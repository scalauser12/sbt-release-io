package io.release.monorepo.internal.steps

import io.release.ReleasePluginIO
import io.release.ReleaseIOCompat
import sjsonnew.BasicJsonProtocol
import sbt.Keys.*
import sbt.{Setting, *}
import sbt.protocol.testing.codec.TestResultFormats

import java.io.File
import _root_.io.release.runtime.sbt.SbtCompat

// Source-split because sbt 1 and sbt 2 expose different test task result types and caching needs.
private[monorepo] object MonorepoStepTestCompat:

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
          remainingCommands = SbtCompat.FailureCommand :: state.remainingCommands
        )
      }
      .value

  def failureCommandCleanTaskSetting(marker: File): Setting[?] =
    Global / Keys.clean := Def
      .task[Unit] {
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
    ReleasePluginIO.autoImport.releaseIODiagnosticsSnapshotDependencies := Def
      .task[Seq[ModuleID]] {
        sbt.IO.write(marker, "ran")
        dependencies
      }
      .updateState { (state: State, _: Seq[ModuleID]) =>
        state.copy(
          remainingCommands = SbtCompat.FailureCommand :: state.remainingCommands
        )
      }
      .value

  def failureCommandVersionTaskSetting(project: ProjectRef, marker: File): Setting[?] =
    project / ReleasePluginIO.autoImport.releaseIOVersioningReleaseVersion := Def.uncached {
      Def
        .task(())
        .updateState { (state: State, _: Unit) =>
          state.copy(
            remainingCommands = SbtCompat.FailureCommand :: state.remainingCommands
          )
        }
        .value
      sbt.IO.write(marker, "ran")
      val releaseFn: String => String = currentVersion => currentVersion.stripSuffix("-SNAPSHOT")
      releaseFn
    }

  def failureCommandNextVersionTaskSetting(project: ProjectRef, marker: File): Setting[?] =
    project / ReleasePluginIO.autoImport.releaseIOVersioningNextVersion := Def.uncached {
      Def
        .task(())
        .updateState { (state: State, _: Unit) =>
          state.copy(
            remainingCommands = SbtCompat.FailureCommand :: state.remainingCommands
          )
        }
        .value
      sbt.IO.write(marker, "ran")
      val nextFn: String => String = _ => "0.2.0-SNAPSHOT"
      nextFn
    }

  def stateMutationNextVersionTaskSetting(
      project: ProjectRef,
      key: AttributeKey[String],
      value: String
  ): Setting[?] =
    project / ReleasePluginIO.autoImport.releaseIOVersioningNextVersion := Def.uncached {
      Def
        .task(())
        .updateState { (state: State, _: Unit) =>
          state.put(key, value)
        }
        .value
      val nextFn: String => String = _ => "0.2.0-SNAPSHOT"
      nextFn
    }

  def throwingPublishSkipSetting: Setting[?] =
    publish / skip := { throw new RuntimeException("publish/skip eval error"); false }
