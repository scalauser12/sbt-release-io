package io.release.monorepo.internal.steps

import io.release.ReleaseSharedKeys
import io.release.ReleaseIOCompat
import sjsonnew.BasicJsonProtocol
import sbt.Keys.*
import sbt.{Setting, *}
import sbt.protocol.testing.codec.TestResultFormats

import java.io.File
import java.util.concurrent.atomic.AtomicInteger
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
    ReleaseSharedKeys.releaseIODiagnosticsSnapshotDependencies := Def
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

  def managedClasspathSetting(marker: File): Setting[?] =
    Test / Keys.managedClasspath := {
      Def.uncached {
        sbt.IO.write(marker, "ran")
        Nil
      }
    }

  def failureCommandVersionTaskSetting(project: ProjectRef, marker: File): Setting[?] =
    project / ReleaseSharedKeys.releaseIOVersioningReleaseVersion := Def.uncached {
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
    project / ReleaseSharedKeys.releaseIOVersioningNextVersion := Def.uncached {
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
    project / ReleaseSharedKeys.releaseIOVersioningNextVersion := Def.uncached {
      Def
        .task(())
        .updateState { (state: State, _: Unit) =>
          state.put(key, value)
        }
        .value
      val nextFn: String => String = _ => "0.2.0-SNAPSHOT"
      nextFn
    }

  def countedVersionTaskSettings(
      project: ProjectRef,
      releaseCalls: AtomicInteger,
      nextCalls: AtomicInteger
  ): Seq[Setting[?]] =
    Seq(
      project / ReleaseSharedKeys.releaseIOVersioningReleaseVersion := Def.uncached {
        releaseCalls.incrementAndGet()
        val releaseFn: String => String = _.stripSuffix("-SNAPSHOT")
        releaseFn
      },
      project / ReleaseSharedKeys.releaseIOVersioningNextVersion    := Def.uncached {
        nextCalls.incrementAndGet()
        val nextFn: String => String = _ => "0.2.0-SNAPSHOT"
        nextFn
      }
    )

  def throwingPublishSkipSetting: Setting[?] =
    publish / skip := { throw new RuntimeException("publish/skip eval error"); false }

  def firstPublishSkipEvaluationReturnsTrue(marker: File): Setting[?] =
    publish / skip := Def
      .task[Boolean] {
        val evaluations = if marker.exists() then sbt.IO.read(marker).trim.toInt else 0
        sbt.IO.write(marker, (evaluations + 1).toString)
        evaluations == 0
      }
      .value

  def countedPublishSkipSetting(marker: File, skipped: Boolean): Setting[?] =
    publish / skip := Def
      .task[Boolean] {
        val evaluations = if marker.exists() then sbt.IO.read(marker).trim.toInt else 0
        sbt.IO.write(marker, (evaluations + 1).toString)
        skipped
      }
      .value

  def countedProjectPublishSkipSetting(
      project: ProjectRef,
      marker: File,
      skipped: Boolean
  ): Setting[?] =
    project / publish / skip := Def
      .task[Boolean] {
        val evaluations = if marker.exists() then sbt.IO.read(marker).trim.toInt else 0
        sbt.IO.write(marker, (evaluations + 1).toString)
        skipped
      }
      .value

  def observedPublishSkipSetting(
      project: ProjectRef,
      marker: File,
      skipped: Boolean
  ): Setting[?] =
    project / publish / skip := Def
      .task[Boolean] {
        sbt.IO.touch(marker)
        skipped
      }
      .value

  def publishSkipWithScalaStateMutation(
      project: ProjectRef,
      nextScalaVersion: String,
      skipped: Boolean
  ): Setting[?] =
    project / publish / skip := Def
      .task[Boolean](skipped)
      .updateState { (state: State, _: Boolean) =>
        _root_.io.release.runtime.sbt.SbtRuntime.appendWithSession(
          state,
          Seq(project / scalaVersion := nextScalaVersion)
        )
      }
      .value

  def publishSkipWithConditionalTargetStateMutation(
      project: ProjectRef,
      enabledKey: AttributeKey[Boolean],
      target: Resolver
  ): Setting[?] =
    project / publish / skip := Def
      .task[Boolean](false)
      .updateState { (state: State, _: Boolean) =>
        if state.get(enabledKey).contains(true) then
          _root_.io.release.runtime.sbt.SbtRuntime.appendWithSession(
            state,
            Seq(project / publishTo := Some(target))
          )
        else state
      }
      .value

  def publishActionWithScalaStateMutation(
      project: ProjectRef,
      nextScalaVersion: String,
      marker: File
  ): Setting[?] =
    project / ReleaseSharedKeys.releaseIOPublishAction := Def
      .task[Unit] {
        sbt.IO.touch(marker)
      }
      .updateState { (state: State, _: Unit) =>
        _root_.io.release.runtime.sbt.SbtRuntime.appendWithSession(
          state,
          Seq(project / scalaVersion := nextScalaVersion)
        )
      }
      .value

  def publishActionWithPersistentScalaStateMutation(
      project: ProjectRef,
      nextScalaVersion: String,
      marker: File
  ): Setting[?] =
    project / ReleaseSharedKeys.releaseIOPublishAction := Def
      .task[Unit] {
        sbt.IO.touch(marker)
      }
      .updateState { (state: State, _: Unit) =>
        _root_.io.release.runtime.sbt.SbtRuntime.appendSessionSettings(
          state,
          Seq(project / scalaVersion := nextScalaVersion)
        )
      }
      .value

  def publishActionWithPersistentCrossScalaVersionsMutation(
      project: ProjectRef,
      nextCrossScalaVersions: Seq[String],
      marker: File
  ): Setting[?] =
    project / ReleaseSharedKeys.releaseIOPublishAction := Def
      .task[Unit](())
      .updateState { (state: State, _: Unit) =>
        val liveScalaVersion = Project.extract(state).get(project / scalaVersion)
        sbt.IO.append(marker, s"$liveScalaVersion\n")
        _root_.io.release.runtime.sbt.SbtRuntime.appendSessionSettings(
          state,
          Seq(project / crossScalaVersions := nextCrossScalaVersions)
        )
      }
      .value

  def failureCommandPublishSkipSetting(
      marker: File,
      skipped: Boolean = false
  ): Setting[?] =
    publish / skip := Def
      .task[Boolean] {
        sbt.IO.write(marker, "ran")
        skipped
      }
      .updateState { (state: State, _: Boolean) =>
        state.copy(
          remainingCommands = SbtCompat.FailureCommand :: state.remainingCommands
        )
      }
      .value

  def failureCommandPublishTargetSetting(marker: File): Setting[?] =
    publishTo := Def
      .task[Option[Resolver]] {
        sbt.IO.write(marker, "ran")
        Some(Resolver.file("local-test", marker.getParentFile))
      }
      .updateState { (state: State, _: Option[Resolver]) =>
        state.copy(
          remainingCommands = SbtCompat.FailureCommand :: state.remainingCommands
        )
      }
      .value
