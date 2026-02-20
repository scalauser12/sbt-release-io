package io.release

import io.release.steps.ReleaseSteps
import sbt.*

import scala.language.implicitConversions

/** Shared setting keys, factory methods, and implicit conversions for release-io plugins.
  * Both the default [[ReleasePluginIO]] and custom [[ReleasePluginIOLike]] derivations can
  * import from here.
  *
  * {{{
  * import io.release.ReleaseIO.*
  * }}}
  */
object ReleaseIO {

  // ── Setting keys ──────────────────────────────────────────────────────────

  /** The ordered sequence of release steps to execute. Defaults to [[steps.ReleaseSteps.defaults]]. */
  val releaseIOProcess: SettingKey[Seq[ReleaseStepIO]] =
    settingKey[Seq[ReleaseStepIO]]("The sequence of IO release steps to execute")

  /** When `true`, steps with `enableCrossBuild = true` are executed once per `crossScalaVersions`.
    * Can also be enabled via the `cross` command-line argument to `releaseIO`.
    */
  val releaseIOCrossBuild: SettingKey[Boolean] =
    settingKey[Boolean]("Whether to enable cross-building during release")

  /** When `true`, the `publishArtifacts` step is skipped entirely. */
  val releaseIOSkipPublish: SettingKey[Boolean] =
    settingKey[Boolean]("Whether to skip publish during release")

  // ── Factory methods ───────────────────────────────────────────────────────

  /** Create a release step that runs a task. */
  def stepTask[A](key: TaskKey[A], enableCrossBuild: Boolean = false): ReleaseStepIO =
    ReleaseStepIO.fromTask(key, enableCrossBuild)

  /** Create a release step that runs an aggregated task across sub-projects. */
  def stepTaskAggregated[A](key: TaskKey[A], enableCrossBuild: Boolean = false): ReleaseStepIO =
    ReleaseStepIO.fromTaskAggregated(key, enableCrossBuild)

  /** Create a release step that runs an input task. */
  def stepInputTask[A](
      key: InputKey[A],
      args: String = "",
      enableCrossBuild: Boolean = false
  ): ReleaseStepIO =
    ReleaseStepIO.fromInputTask(key, args, enableCrossBuild)

  /** Create a release step that runs an sbt command. */
  def stepCommand(command: String): ReleaseStepIO =
    ReleaseStepIO.fromCommand(command)

  /** Create a release step that runs a command and drains remaining enqueued commands. */
  def stepCommandAndRemaining(command: String): ReleaseStepIO =
    ReleaseStepIO.fromCommandAndRemaining(command)

  // ── sbt-release compatibility conversions ─────────────────────────────────

  implicit val sbtReleaseStepConversion
      : sbtrelease.ReleasePlugin.autoImport.ReleaseStep => ReleaseStepIO =
    SbtReleaseCompat.releaseStepToReleaseStepIO

  implicit val sbtReleaseStateTransformConversion: (State => State) => ReleaseStepIO =
    SbtReleaseCompat.stateTransformToReleaseStepIO
}
