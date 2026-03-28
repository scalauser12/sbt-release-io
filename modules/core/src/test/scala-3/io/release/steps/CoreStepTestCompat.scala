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
    ReleaseIO.releaseIOPublishArtifactsAction := Def
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

  def throwingPublishToSetting: Setting[?] =
    publishTo := { throw new RuntimeException("publishTo eval error"); None }

  def throwingPublishSkipSetting: Setting[?] =
    publish / skip := { throw new RuntimeException("publish/skip eval error"); false }
