package io.release

import sbt.State
import sbtrelease.Vcs

/** Immutable context threaded through each release step during both the check and action phases.
  *
  * Created by [[ReleasePluginIOLike.initialContext]] at the start of the release command,
  * then passed through [[ReleaseStepIO.compose]] which threads it sequentially through
  * every step. Steps return a new `ReleaseContext` with updated state, versions, or flags.
  *
  * @param state       the current `sbt.State`, updated between steps
  * @param versions    `(releaseVersion, nextVersion)` pair, set by `inquireVersions`
  * @param vcs         VCS adapter (git), set by `initializeVcs`
  * @param skipTests   when true, test steps are skipped
  * @param skipPublish when true, publish steps are skipped
  * @param interactive when true, steps may prompt for user input
  * @param attributes  arbitrary key-value store for inter-step communication
  * @param failed      set to true by the composer on step failure; subsequent steps are skipped
  */
case class ReleaseContext(
    state: State,
    versions: Option[(String, String)] = None, // (releaseVersion, nextVersion)
    vcs: Option[Vcs] = None,
    skipTests: Boolean = false,
    skipPublish: Boolean = false,
    interactive: Boolean = false,
    attributes: Map[String, String] = Map.empty,
    failed: Boolean = false
) {
  def withVersions(release: String, next: String): ReleaseContext =
    copy(versions = Some((release, next)))

  def withVcs(v: Vcs): ReleaseContext =
    copy(vcs = Some(v))

  def attr(key: String): Option[String] = attributes.get(key)

  def withAttr(key: String, value: String): ReleaseContext =
    copy(attributes = attributes + (key -> value))

  def releaseVersion: Option[String] = versions.map(_._1)
  def nextVersion: Option[String]    = versions.map(_._2)

  def fail: ReleaseContext = copy(failed = true)
}
