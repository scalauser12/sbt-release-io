package io.release.core.internal.steps

import io.release.ReleasePluginIO
import io.release.ReleaseIOCompat
import sjsonnew.BasicJsonProtocol
import sbt.Keys.*
import sbt.{Def, Setting, State, *}
import sbt.protocol.testing.codec.TestResultFormats

import java.io.File
import _root_.io.release.runtime.sbt.SbtCompat

private[steps] object CoreStepTestCompat:

  private object TestResultJsonProtocol extends BasicJsonProtocol, TestResultFormats
  import TestResultJsonProtocol.given

  def failureCommandPublishTaskSetting(marker: File): Setting[?] =
    ReleasePluginIO.autoImport.releaseIOPublishAction := Def
      .task[Unit] {
        sbt.IO.write(marker, "ran")
      }
      .updateState { (state: State, _: Unit) =>
        state.copy(
          remainingCommands = SbtCompat.FailureCommand :: state.remainingCommands
        )
      }
      .value

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

  def failureCommandVersionTaskSetting(marker: File): Setting[?] =
    ReleasePluginIO.autoImport.releaseIOVersioningReleaseVersion := Def
      .task[String => String] {
        sbt.IO.write(marker, "ran")
        { currentVersion =>
          currentVersion.stripSuffix("-SNAPSHOT")
        }
      }
      .updateState { (state: State, _: String => String) =>
        state.copy(
          remainingCommands = SbtCompat.FailureCommand :: state.remainingCommands
        )
      }
      .value

  def failureCommandNextVersionTaskSetting(marker: File): Setting[?] =
    ReleasePluginIO.autoImport.releaseIOVersioningNextVersion := Def
      .task[String => String] {
        sbt.IO.write(marker, "ran")
        _ => "0.2.0-SNAPSHOT"
      }
      .updateState { (state: State, _: String => String) =>
        state.copy(
          remainingCommands = SbtCompat.FailureCommand :: state.remainingCommands
        )
      }
      .value

  def failureCommandCommitMessageSetting(marker: File): Setting[?] =
    ReleasePluginIO.autoImport.releaseIOVcsReleaseCommitMessage := Def
      .task[String] {
        sbt.IO.write(marker, "ran")
        "Setting version"
      }
      .updateState { (state: State, _: String) =>
        state.copy(
          remainingCommands = SbtCompat.FailureCommand :: state.remainingCommands
        )
      }
      .value

  def failureCommandNextCommitMessageSetting(marker: File): Setting[?] =
    ReleasePluginIO.autoImport.releaseIOVcsNextCommitMessage := Def
      .task[String] {
        sbt.IO.write(marker, "ran")
        "Setting next version"
      }
      .updateState { (state: State, _: String) =>
        state.copy(
          remainingCommands = SbtCompat.FailureCommand :: state.remainingCommands
        )
      }
      .value

  def failureCommandTagNameSetting(marker: File): Setting[?] =
    ReleasePluginIO.autoImport.releaseIOVcsTagName := Def
      .task[String] {
        sbt.IO.write(marker, "ran")
        "v1.0.0"
      }
      .updateState { (state: State, _: String) =>
        state.copy(
          remainingCommands = SbtCompat.FailureCommand :: state.remainingCommands
        )
      }
      .value

  def failureCommandTagCommentSetting(marker: File): Setting[?] =
    ReleasePluginIO.autoImport.releaseIOVcsTagComment := Def
      .task[String] {
        sbt.IO.write(marker, "ran")
        "Releasing 1.0.0"
      }
      .updateState { (state: State, _: String) =>
        state.copy(
          remainingCommands = SbtCompat.FailureCommand :: state.remainingCommands
        )
      }
      .value

  def throwingPublishToSetting: Setting[?] =
    publishTo := { throw new RuntimeException("publishTo eval error"); None }

  def throwingPublishSkipSetting: Setting[?] =
    publish / skip := { throw new RuntimeException("publish/skip eval error"); false }
