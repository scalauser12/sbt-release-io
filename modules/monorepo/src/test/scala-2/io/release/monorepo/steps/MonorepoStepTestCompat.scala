package io.release.monorepo.internal.steps

import _root_.io.release.runtime.sbt.SbtCompat
import io.release.ReleaseIOCompat
import io.release.ReleaseSharedKeys
import sbt.*
import sbt.Keys.*
import sbt.Setting

import java.io.File
import java.util.concurrent.atomic.AtomicInteger

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
    ReleaseSharedKeys.releaseIODiagnosticsSnapshotDependencies := Def
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
    project / ReleaseSharedKeys.releaseIOVersioningReleaseVersion := {
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
    project / ReleaseSharedKeys.releaseIOVersioningNextVersion := {
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
    project / ReleaseSharedKeys.releaseIOVersioningNextVersion := {
      val _ = Def
        .task(())
        .updateState { (state: State, _: Unit) =>
          state.put(key, value)
        }
        .value
      (_: String) => "0.2.0-SNAPSHOT"
    }

  def countedVersionTaskSettings(
      project: ProjectRef,
      releaseCalls: AtomicInteger,
      nextCalls: AtomicInteger
  ): Seq[Setting[?]] =
    Seq(
      project / ReleaseSharedKeys.releaseIOVersioningReleaseVersion := {
        releaseCalls.incrementAndGet()
        (currentVersion: String) => currentVersion.stripSuffix("-SNAPSHOT")
      },
      project / ReleaseSharedKeys.releaseIOVersioningNextVersion    := {
        nextCalls.incrementAndGet()
        (_: String) => "0.2.0-SNAPSHOT"
      }
    )

  def throwingPublishSkipSetting: Setting[?] =
    publish / skip := { throw new RuntimeException("publish/skip eval error"); false }

  def firstPublishSkipEvaluationReturnsTrue(marker: File): Setting[?] =
    publish / skip := Def.task {
      val evaluations = if (marker.exists()) sbt.IO.read(marker).trim.toInt else 0
      sbt.IO.write(marker, (evaluations + 1).toString)
      evaluations == 0
    }.value

  def countedPublishSkipSetting(marker: File, skipped: Boolean): Setting[?] =
    publish / skip := Def.task {
      val evaluations = if (marker.exists()) sbt.IO.read(marker).trim.toInt else 0
      sbt.IO.write(marker, (evaluations + 1).toString)
      skipped
    }.value

  def countedProjectPublishSkipSetting(
      project: ProjectRef,
      marker: File,
      skipped: Boolean
  ): Setting[?] =
    project / publish / skip := Def.task {
      val evaluations = if (marker.exists()) sbt.IO.read(marker).trim.toInt else 0
      sbt.IO.write(marker, (evaluations + 1).toString)
      skipped
    }.value

  def observedPublishSkipSetting(
      project: ProjectRef,
      marker: File,
      skipped: Boolean
  ): Setting[?] =
    project / publish / skip := Def.task {
      sbt.IO.touch(marker)
      skipped
    }.value

  def publishSkipWithScalaStateMutation(
      project: ProjectRef,
      nextScalaVersion: String,
      skipped: Boolean
  ): Setting[?] =
    project / publish / skip := Def
      .task(skipped)
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
      .task(false)
      .updateState { (state: State, _: Boolean) =>
        if (state.get(enabledKey).contains(true))
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
      .task {
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
      .task {
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
      .task {
        sbt.IO.write(marker, "ran")
        Some(Resolver.file("local-test", marker.getParentFile))
      }
      .updateState { (state: State, _: Option[Resolver]) =>
        state.copy(
          remainingCommands = SbtCompat.FailureCommand :: state.remainingCommands
        )
      }
      .value
}
