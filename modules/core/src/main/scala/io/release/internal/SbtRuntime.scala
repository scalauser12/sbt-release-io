package io.release.internal

import cats.effect.IO
import io.release.CrossBuildSupport
import sbt.{internal => _, *}

/** Thin wrappers over sbt state/extraction APIs used by built-in release code. */
private[release] object SbtRuntime {

  def extracted(state: State): Extracted =
    Project.extract(state)

  def getSetting[A](state: State, key: SettingKey[A]): A =
    extracted(state).get(key)

  def runTask[A](state: State, key: TaskKey[A]): (State, A) =
    extracted(state).runTask(key, state)

  def runInputTask[A](state: State, key: InputKey[A], args: String): (State, A) =
    extracted(state).runInputTask(key, args, state)

  def appendWithSession(state: State, settings: Seq[Setting[?]]): State =
    extracted(state).appendWithSession(settings, state)

  def switchScalaVersion(state: State, version: String): IO[State] =
    CrossBuildSupport.switchScalaVersion(state, version)
}
