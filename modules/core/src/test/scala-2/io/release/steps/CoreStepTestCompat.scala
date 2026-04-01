package io.release.steps

import io.release.ReleaseIO
import io.release.ReleaseIOCompat
import sbt.*
import sbt.Def
import sbt.Keys.*
import sbt.Setting
import sbt.State

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

  def failureCommandVersionTaskSetting(marker: File): Setting[?] =
    ReleaseIO.releaseIOVersion := Def
      .task {
        sbt.IO.write(marker, "ran")
        (currentVersion: String) => currentVersion.stripSuffix("-SNAPSHOT")
      }
      .updateState { (state: State, _: String => String) =>
        state.copy(
          remainingCommands =
            _root_.io.release.internal.SbtCompat.FailureCommand :: state.remainingCommands
        )
      }
      .value

  def failureCommandNextVersionTaskSetting(marker: File): Setting[?] =
    ReleaseIO.releaseIONextVersion := Def
      .task {
        sbt.IO.write(marker, "ran")
        (_: String) => "0.2.0-SNAPSHOT"
      }
      .updateState { (state: State, _: String => String) =>
        state.copy(
          remainingCommands =
            _root_.io.release.internal.SbtCompat.FailureCommand :: state.remainingCommands
        )
      }
      .value

  def failureCommandCommitMessageSetting(marker: File): Setting[?] =
    ReleaseIO.releaseIOCommitMessage := Def
      .task {
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
    ReleaseIO.releaseIONextCommitMessage := Def
      .task {
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
    ReleaseIO.releaseIOTagName := Def
      .task {
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
    ReleaseIO.releaseIOTagComment := Def
      .task {
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
}
