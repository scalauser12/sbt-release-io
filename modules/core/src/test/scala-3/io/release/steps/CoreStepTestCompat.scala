package io.release.steps

import io.release.{ReleaseIO, ReleaseIOCompat}
import sjsonnew.BasicJsonProtocol
import sbt.Keys.*
import sbt.{Def, Setting, State, *}
import sbt.protocol.testing.codec.TestResultFormats

import java.io.File

private[steps] object CoreStepTestCompat:

  private object TestResultJsonProtocol extends BasicJsonProtocol, TestResultFormats
  import TestResultJsonProtocol.given

  def failureCommandPublishTaskSetting(marker: File): Setting[?] =
    ReleaseIO.releaseIOPublishAction := Def
      .task[Unit] {
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
      .task[sbt.protocol.testing.TestResult] {
        sbt.IO.write(marker, "ran")
        val result: sbt.protocol.testing.TestResult = sbt.protocol.testing.TestResult.Passed
        result
      }
      .updateState { (state: State, _: sbt.protocol.testing.TestResult) =>
        state.copy(
          remainingCommands =
            _root_.io.release.internal.SbtCompat.FailureCommand :: state.remainingCommands
        )
      }
      .value

  def failureCommandVersionTaskSetting(marker: File): Setting[?] =
    ReleaseIO.releaseIOVersioningReleaseVersion := Def
      .task[String => String] {
        sbt.IO.write(marker, "ran")
        { currentVersion =>
          currentVersion.stripSuffix("-SNAPSHOT")
        }
      }
      .updateState { (state: State, _: String => String) =>
        state.copy(
          remainingCommands =
            _root_.io.release.internal.SbtCompat.FailureCommand :: state.remainingCommands
        )
      }
      .value

  def failureCommandNextVersionTaskSetting(marker: File): Setting[?] =
    ReleaseIO.releaseIOVersioningNextVersion := Def
      .task[String => String] {
        sbt.IO.write(marker, "ran")
        _ =>
          "0.2.0-SNAPSHOT"
      }
      .updateState { (state: State, _: String => String) =>
        state.copy(
          remainingCommands =
            _root_.io.release.internal.SbtCompat.FailureCommand :: state.remainingCommands
        )
      }
      .value

  def failureCommandCommitMessageSetting(marker: File): Setting[?] =
    ReleaseIO.releaseIOVcsReleaseCommitMessage := Def
      .task[String] {
        sbt.IO.write(marker, "ran")
        "Setting version"
      }
      .updateState { (state: State, _: String) =>
        state.copy(
          remainingCommands =
            _root_.io.release.internal.SbtCompat.FailureCommand :: state.remainingCommands
        )
      }
      .value

  def failureCommandNextCommitMessageSetting(marker: File): Setting[?] =
    ReleaseIO.releaseIOVcsNextCommitMessage := Def
      .task[String] {
        sbt.IO.write(marker, "ran")
        "Setting next version"
      }
      .updateState { (state: State, _: String) =>
        state.copy(
          remainingCommands =
            _root_.io.release.internal.SbtCompat.FailureCommand :: state.remainingCommands
        )
      }
      .value

  def failureCommandTagNameSetting(marker: File): Setting[?] =
    ReleaseIO.releaseIOVcsTagName := Def
      .task[String] {
        sbt.IO.write(marker, "ran")
        "v1.0.0"
      }
      .updateState { (state: State, _: String) =>
        state.copy(
          remainingCommands =
            _root_.io.release.internal.SbtCompat.FailureCommand :: state.remainingCommands
        )
      }
      .value

  def failureCommandTagCommentSetting(marker: File): Setting[?] =
    ReleaseIO.releaseIOVcsTagComment := Def
      .task[String] {
        sbt.IO.write(marker, "ran")
        "Releasing 1.0.0"
      }
      .updateState { (state: State, _: String) =>
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
