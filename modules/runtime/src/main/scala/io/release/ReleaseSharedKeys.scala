package io.release

import sbt.{internal as _, *}

import scala.concurrent.duration.FiniteDuration

/** Runtime-owned shared key instances reused by core, monorepo, and shared helpers.
  *
  * These keys remain publicly exposed through [[ReleasePluginIO.autoImport]]; this object only
  * centralizes ownership so shared runtime code does not depend on the core plugin module.
  */
private[release] object ReleaseSharedKeys {

  lazy val releaseIODefaultsTagExistsAnswer: SettingKey[Option[String]] =
    SettingKey[Option[String]](
      "releaseIODefaultsTagExistsAnswer",
      "Default action when a release tag already exists"
    )

  lazy val releaseIODefaultsSnapshotDependenciesAnswer: SettingKey[Option[Boolean]] =
    SettingKey[Option[Boolean]](
      "releaseIODefaultsSnapshotDependenciesAnswer",
      "Default decision for continuing when SNAPSHOT dependencies are detected"
    )

  lazy val releaseIODefaultsRemoteCheckFailureAnswer: SettingKey[Option[Boolean]] =
    SettingKey[Option[Boolean]](
      "releaseIODefaultsRemoteCheckFailureAnswer",
      "Default decision for continuing after a remote-check failure"
    )

  lazy val releaseIODefaultsUpstreamBehindAnswer: SettingKey[Option[Boolean]] =
    SettingKey[Option[Boolean]](
      "releaseIODefaultsUpstreamBehindAnswer",
      "Default decision for continuing when the local branch is behind upstream"
    )

  lazy val releaseIODefaultsPushAnswer: SettingKey[Option[Boolean]] =
    SettingKey[Option[Boolean]](
      "releaseIODefaultsPushAnswer",
      "Default decision for whether to push changes at the end of the release"
    )

  lazy val releaseIOVersioningFile: SettingKey[File] =
    SettingKey[File](
      "releaseIOVersioningFile",
      "Path to the version file"
    )

  @transient
  lazy val releaseIOVersioningReleaseVersion: TaskKey[String => String] =
    TaskKey[String => String](
      "releaseIOVersioningReleaseVersion",
      "Function that computes the release version from the current version"
    )

  @transient
  lazy val releaseIOVersioningNextVersion: TaskKey[String => String] =
    TaskKey[String => String](
      "releaseIOVersioningNextVersion",
      "Function that computes the next development version from the release version"
    )

  lazy val releaseIOVcsSign: SettingKey[Boolean] =
    SettingKey[Boolean](
      "releaseIOVcsSign",
      "Whether VCS tags and commits are GPG-signed"
    )

  lazy val releaseIOVcsSignOff: SettingKey[Boolean] =
    SettingKey[Boolean](
      "releaseIOVcsSignOff",
      "Whether VCS commits include a Signed-off-by line"
    )

  lazy val releaseIOVcsIgnoreUntrackedFiles: SettingKey[Boolean] =
    SettingKey[Boolean](
      "releaseIOVcsIgnoreUntrackedFiles",
      "Whether untracked files are ignored during clean working dir check"
    )

  lazy val releaseIOVcsRemoteCheckTimeout: SettingKey[FiniteDuration] =
    SettingKey[FiniteDuration](
      "releaseIOVcsRemoteCheckTimeout",
      "Timeout for the remote reachability check performed before push"
    )

  @transient
  lazy val releaseIOPublishAction: TaskKey[Unit] =
    TaskKey[Unit](
      "releaseIOPublishAction",
      "Task that performs the actual publish action"
    )

  @transient
  lazy val releaseIODiagnosticsSnapshotDependencies: TaskKey[Seq[ModuleID]] =
    TaskKey[Seq[ModuleID]](
      "releaseIODiagnosticsSnapshotDependencies",
      "Task that resolves SNAPSHOT dependencies for validation"
    )
}
