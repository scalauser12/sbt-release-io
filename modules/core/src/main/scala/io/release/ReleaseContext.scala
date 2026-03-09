package io.release

import sbt.*
import sbt.internal.util.AttributeMap
import sbtrelease.Vcs

/** Immutable context threaded through each release step during validation and execution.
  *
  * Created by [[ReleasePluginIOLike.initialContext]] at the start of the release command,
  * then passed through [[ReleaseStepIO.compose]] which threads it sequentially through
  * every execute step. Steps return a new `ReleaseContext` with updated state, versions, or flags.
  *
  * @param state        the current `sbt.State`, updated between execute steps
  * @param versions     `(releaseVersion, nextVersion)` pair, set by `inquireVersions`
  * @param vcs          VCS adapter (git), set by `initializeVcs`
  * @param skipTests    when true, test steps are skipped
  * @param skipPublish  when true, publish steps are skipped
  * @param interactive  when true, steps may prompt for user input
  * @param metadataBag  typed inter-step metadata
  * @param failed       set to true by the composer on step failure; subsequent steps are skipped
  */
case class ReleaseContext(
    state: State,
    versions: Option[(String, String)] = None, // (releaseVersion, nextVersion)
    vcs: Option[Vcs] = None,
    skipTests: Boolean = false,
    skipPublish: Boolean = false,
    interactive: Boolean = false,
    metadataBag: AttributeMap = AttributeMap.empty,
    failed: Boolean = false,
    failureCause: Option[Throwable] = None
) extends ReleaseCtx[ReleaseContext] {

  def withState(s: State): ReleaseContext = copy(state = s)

  def withVersions(release: String, next: String): ReleaseContext =
    copy(versions = Some((release, next)))

  def withVcs(v: Vcs): ReleaseContext =
    copy(vcs = Some(v))

  def metadata[A](key: AttributeKey[A]): Option[A] =
    metadataBag.get(key)

  def withMetadata[A](key: AttributeKey[A], value: A): ReleaseContext =
    copy(metadataBag = metadataBag.put(key, value))

  def withoutMetadata[A](key: AttributeKey[A]): ReleaseContext =
    copy(metadataBag = metadataBag.remove(key))

  def releaseVersion: Option[String] = versions.map(_._1)
  def nextVersion: Option[String]    = versions.map(_._2)

  def fail: ReleaseContext                       = copy(failed = true)
  def failWith(cause: Throwable): ReleaseContext = copy(failed = true, failureCause = Some(cause))
}
