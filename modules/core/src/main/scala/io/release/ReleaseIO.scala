package io.release

import cats.effect.IO
import io.release.version.Version
import sbt.{internal as _, *}

/** Shared setting keys and factory methods for release-io plugins.
  * Both the default [[ReleasePluginIO]] and custom [[ReleasePluginIOLike]] derivations can
  * mix in or import from here.
  *
  * Setting keys are singletons defined in the companion object. Custom plugins should
  * ''not'' define `object autoImport extends ReleaseIO` when coexisting with
  * [[ReleasePluginIO]] — that causes ambiguous references in build.sbt.
  * [[ReleasePluginIO]] is auto-enabled and its keys are in scope automatically.
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
  val releaseIOVersionFileContents: SettingKey[(File, String) => IO[String]] =
    ReleaseIO._releaseIOVersionFileContents

  // ── Forked sbt-release keys ─────────────────────────────────────────────

  /** Path to the version file (e.g. `version.sbt`). */
  val releaseIOVersionFile: SettingKey[File] = ReleaseIO._releaseIOVersionFile

  /** When `true`, the version file uses `ThisBuild / version` instead of `version`. */
  val releaseIOUseGlobalVersion: SettingKey[Boolean] = ReleaseIO._releaseIOUseGlobalVersion

  /** When `true`, VCS tags and commits are GPG-signed. */
  val releaseIOVcsSign: SettingKey[Boolean] = ReleaseIO._releaseIOVcsSign

  /** When `true`, VCS commits include a `Signed-off-by` line. */
  val releaseIOVcsSignOff: SettingKey[Boolean] = ReleaseIO._releaseIOVcsSignOff

  /** When `true`, untracked files do not cause the clean-working-dir check to fail. */
  val releaseIOIgnoreUntrackedFiles: SettingKey[Boolean] = ReleaseIO._releaseIOIgnoreUntrackedFiles

  /** The current version at evaluation time. Useful as a dependency for tag/commit message tasks
    * so they pick up the version set by `setReleaseVersion` via `appendWithSession`.
    */
  @transient
  val releaseIORuntimeVersion: TaskKey[String] = ReleaseIO._releaseIORuntimeVersion

  /** Tag name for the release. Default: `s"v$$version"`. */
  @transient
  val releaseIOTagName: TaskKey[String] = ReleaseIO._releaseIOTagName

  /** Tag comment. Default: `s"Releasing $$version"`. */
  @transient
  val releaseIOTagComment: TaskKey[String] = ReleaseIO._releaseIOTagComment

  /** Commit message for the release version commit. */
  @transient
  val releaseIOCommitMessage: TaskKey[String] = ReleaseIO._releaseIOCommitMessage

  /** Commit message for the next snapshot version commit. */
  @transient
  val releaseIONextCommitMessage: TaskKey[String] = ReleaseIO._releaseIONextCommitMessage

  /** Function that computes the release version from the current version. */
  @transient
  val releaseIOVersion: TaskKey[String => String] = ReleaseIO._releaseIOVersion

  /** Function that computes the next development version from the release version. */
  @transient
  val releaseIONextVersion: TaskKey[String => String] = ReleaseIO._releaseIONextVersion

  /** Version bump strategy. */
  @transient
  val releaseIOVersionBump: TaskKey[Version.Bump] =
    ReleaseIO._releaseIOVersionBump

  /** Task that resolves SNAPSHOT dependencies for validation. */
  @transient
  val releaseIOSnapshotDependencies: TaskKey[Seq[ModuleID]] =
    ReleaseIO._releaseIOSnapshotDependencies

  /** Task that performs the actual publish action. Default: `publish`. */
  @transient
  val releaseIOPublishArtifactsAction: TaskKey[Unit] = ReleaseIO._releaseIOPublishArtifactsAction

  /** When false, skips publishTo/skip validation in the publishArtifacts step.
    * Useful when overriding `releaseIOPublishArtifactsAction` with a custom publish task.
    */
  val releaseIOPublishArtifactsChecks: SettingKey[Boolean] =
    ReleaseIO._releaseIOPublishArtifactsChecks

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

  private[release] lazy val _releaseIOVersionFileContents
      : SettingKey[(File, String) => IO[String]] =
    SettingKey[(File, String) => IO[String]](
      "releaseIOVersionFileContents",
      "Function that produces version file contents: (file, version) => IO[contents]"
    )

  // ── Forked sbt-release keys ──────────────────────────────────────────────

  private[release] lazy val _releaseIOVersionFile: SettingKey[File] =
    SettingKey[File]("releaseIOVersionFile", "Path to the version file")

  private[release] lazy val _releaseIOUseGlobalVersion: SettingKey[Boolean] =
    SettingKey[Boolean](
      "releaseIOUseGlobalVersion",
      "Whether the version file uses ThisBuild / version"
    )

  private[release] lazy val _releaseIOVcsSign: SettingKey[Boolean] =
    SettingKey[Boolean]("releaseIOVcsSign", "Whether VCS tags and commits are GPG-signed")

  private[release] lazy val _releaseIOVcsSignOff: SettingKey[Boolean] =
    SettingKey[Boolean]("releaseIOVcsSignOff", "Whether VCS commits include a Signed-off-by line")

  private[release] lazy val _releaseIOIgnoreUntrackedFiles: SettingKey[Boolean] =
    SettingKey[Boolean](
      "releaseIOIgnoreUntrackedFiles",
      "Whether untracked files are ignored during clean working dir check"
    )

  @transient
  private[release] lazy val _releaseIORuntimeVersion: TaskKey[String] =
    TaskKey[String](
      "releaseIORuntimeVersion",
      "The current version at evaluation time (used by tag/commit message tasks)"
    )

  @transient
  private[release] lazy val _releaseIOTagName: TaskKey[String] =
    TaskKey[String]("releaseIOTagName", "Tag name for the release")

  @transient
  private[release] lazy val _releaseIOTagComment: TaskKey[String] =
    TaskKey[String]("releaseIOTagComment", "Tag comment for the release")

  @transient
  private[release] lazy val _releaseIOCommitMessage: TaskKey[String] =
    TaskKey[String]("releaseIOCommitMessage", "Commit message for the release version commit")

  @transient
  private[release] lazy val _releaseIONextCommitMessage: TaskKey[String] =
    TaskKey[String](
      "releaseIONextCommitMessage",
      "Commit message for the next snapshot version commit"
    )

  @transient
  private[release] lazy val _releaseIOVersion: TaskKey[String => String] =
    TaskKey[String => String](
      "releaseIOVersion",
      "Function that computes the release version from the current version"
    )

  @transient
  private[release] lazy val _releaseIONextVersion: TaskKey[String => String] =
    TaskKey[String => String](
      "releaseIONextVersion",
      "Function that computes the next development version from the release version"
    )

  @transient
  private[release] lazy val _releaseIOVersionBump: TaskKey[Version.Bump] =
    TaskKey[Version.Bump](
      "releaseIOVersionBump",
      "Version bump strategy"
    )

  @transient
  private[release] lazy val _releaseIOSnapshotDependencies: TaskKey[Seq[ModuleID]] =
    TaskKey[Seq[ModuleID]](
      "releaseIOSnapshotDependencies",
      "Task that resolves SNAPSHOT dependencies for validation"
    )

  @transient
  private[release] lazy val _releaseIOPublishArtifactsAction: TaskKey[Unit] =
    TaskKey[Unit](
      "releaseIOPublishArtifactsAction",
      "Task that performs the actual publish action"
    )

  private[release] lazy val _releaseIOPublishArtifactsChecks: SettingKey[Boolean] =
    SettingKey[Boolean](
      "releaseIOPublishArtifactsChecks",
      "Whether to run publishTo validation checks for the publish step"
    )
}
