package io.release

import cats.effect.IO
import sbt.*

/** Shared setting keys and factory methods for release-io plugins.
  * Both the default [[ReleasePluginIO]] and custom [[ReleasePluginIOLike]] derivations can
  * mix in or import from here.
  *
  * Setting keys are singletons defined in the companion object, so multiple plugins can
  * safely `extends ReleaseIO` or define `object autoImport extends ReleaseIO` without
  * creating duplicate key instances.
  *
  * {{{
  * import io.release.ReleaseIO.*
  * // or
  * object autoImport extends ReleaseIO
  * }}}
  */
trait ReleaseIO {

  // ── Setting keys (delegated to singleton in companion object) ───────────

  /** The ordered sequence of release steps to execute. Defaults to [[steps.ReleaseSteps.defaults]].
    * Resource-aware steps should be added by overriding `releaseProcess` in a
    * [[ReleasePluginIOLike]] subclass, where the resource type `T` is known at compile time.
    */
  val releaseIOProcess: SettingKey[Seq[ReleaseStepIO]] = ReleaseIO._releaseIOProcess

  /** When `true`, steps with `enableCrossBuild = true` are executed once per `crossScalaVersions`.
    * Can also be enabled via the `cross` command-line argument to `releaseIO`.
    */
  val releaseIOCrossBuild: SettingKey[Boolean] = ReleaseIO._releaseIOCrossBuild

  /** When `true`, the `publishArtifacts` step is skipped entirely. */
  val releaseIOSkipPublish: SettingKey[Boolean] = ReleaseIO._releaseIOSkipPublish

  /** When `true`, release steps may prompt for confirmation/input (versions, push, etc.). */
  val releaseIOInteractive: SettingKey[Boolean] = ReleaseIO._releaseIOInteractive

  /** Function that reads the current version string from the version file.
    * Default parses the standard sbt `[ThisBuild /] version := "x.y.z"` format.
    */
  val releaseIOReadVersion: SettingKey[File => IO[String]] = ReleaseIO._releaseIOReadVersion

  /** Function that produces the version file contents for a given version.
    * Receives `(versionFile, newVersion)` and returns `IO[newFileContents]`.
    * The file parameter allows reading existing content for partial updates.
    */
  val releaseIOWriteVersion: SettingKey[(File, String) => IO[String]] =
    ReleaseIO._releaseIOWriteVersion

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

  // ── Resource-aware factory methods ─────────────────────────────────

  /** Create a resource-aware release step from a named IO action.
    *
    * {{{
    * val notifySlack: HttpClient => ReleaseStepIO = resourceStep("notify-slack") { client => ctx =>
    *   IO { client.post("/slack", s"Released $${ctx.versions}"); ctx }
    * }
    * }}}
    */
  def resourceStep[T](name: String, enableCrossBuild: Boolean = false)(
      f: T => ReleaseContext => IO[ReleaseContext]
  ): T => ReleaseStepIO =
    (t: T) => ReleaseStepIO(name, f(t), enableCrossBuild = enableCrossBuild)

  /** Create a resource-aware release step with a check phase. */
  def resourceStepWithCheck[T](name: String, enableCrossBuild: Boolean = false)(
      action: T => ReleaseContext => IO[ReleaseContext]
  )(
      check: T => ReleaseContext => IO[ReleaseContext]
  ): T => ReleaseStepIO =
    (t: T) => ReleaseStepIO(name, action(t), check(t), enableCrossBuild)

}

object ReleaseIO extends ReleaseIO {

  // Canonical key definitions — created exactly once, shared across all mix-ins.
  // Use explicit SettingKey constructors (not the settingKey macro) to decouple
  // the key name from the val name.
  private[release] lazy val _releaseIOProcess: SettingKey[Seq[ReleaseStepIO]] =
    SettingKey[Seq[ReleaseStepIO]](
      "releaseIOProcess",
      "The sequence of IO release steps to execute"
    )

  private[release] lazy val _releaseIOCrossBuild: SettingKey[Boolean] =
    SettingKey[Boolean]("releaseIOCrossBuild", "Whether to enable cross-building during release")

  private[release] lazy val _releaseIOSkipPublish: SettingKey[Boolean] =
    SettingKey[Boolean]("releaseIOSkipPublish", "Whether to skip publish during release")

  private[release] lazy val _releaseIOInteractive: SettingKey[Boolean] =
    SettingKey[Boolean](
      "releaseIOInteractive",
      "Whether to enable interactive prompts during release"
    )

  private[release] lazy val _releaseIOReadVersion: SettingKey[File => IO[String]] =
    SettingKey[File => IO[String]](
      "releaseIOReadVersion",
      "Function to read the current version from the version file"
    )

  private[release] lazy val _releaseIOWriteVersion: SettingKey[(File, String) => IO[String]] =
    SettingKey[(File, String) => IO[String]](
      "releaseIOWriteVersion",
      "Function that produces version file contents: (file, version) => IO[contents]"
    )
}
