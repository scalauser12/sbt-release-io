package io.release

import io.release.core.internal.CoreExecutionState
import io.release.runtime.ExecutionFlags
import io.release.runtime.ReleaseCtx
import io.release.runtime.ReleaseDecisionDefaults
import io.release.vcs.Vcs
import sbt.{internal as _, *}

/** Immutable context threaded through each release step during validation and execution.
  *
  * Created by [[ReleasePluginIOLike.initialContext]] at the start of the release command and
  * then threaded through the compiled release process. Steps return a new `ReleaseContext` with
  * updated state, versions, or flags.
  * Internal startup metadata (command flags and CLI overrides) is threaded through
  * `metadataBag`; the only intentional mirror onto `sbt.State` is the
  * `ReleaseKeys.versions` attribute,
  * which allows sbt task evaluation to observe the chosen release versions.
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
    // (releaseVersion, nextVersion) — also mirrored on State via ReleaseKeys.versions
    // so that sbt settings can read it; see ReleaseKeys.versions Scaladoc.
    versions: Option[(String, String)] = None,
    vcs: Option[Vcs] = None,
    skipTests: Boolean = false,
    skipPublish: Boolean = false,
    interactive: Boolean = false,
    metadataBag: AttributeMap = AttributeMap.empty,
    failed: Boolean = false,
    failureCause: Option[Throwable] = None
) extends ReleaseCtx {
  type Self = ReleaseContext

  override protected def self: ReleaseContext = this

  override def withState(s: State): ReleaseContext = copy(state = s)

  /** Set the release and next version pair, updating both the context field
    * and the sbt State attribute so that sbt tasks can read the versions.
    */
  def withVersions(release: String, next: String): ReleaseContext =
    copy(
      versions = Some((release, next)),
      state = state.put(ReleaseKeys.versions, (release, next))
    )

  override def withVcs(v: Vcs): ReleaseContext =
    copy(vcs = Some(v))

  override def withMetadata[A](
      key: AttributeKey[A],
      value: A
  ): ReleaseContext =
    copy(metadataBag = metadataBag.put(key, value))

  override def withoutMetadata[A](
      key: AttributeKey[A]
  ): ReleaseContext =
    if (metadata(key).isDefined) copy(metadataBag = metadataBag.remove(key))
    else this

  def releaseVersion: Option[String] = versions.map(_._1)
  def nextVersion: Option[String]    = versions.map(_._2)

  private[release] def executionState: Option[CoreExecutionState] =
    metadata(CoreExecutionState.key)

  private[release] def withExecutionState(
      state: CoreExecutionState
  ): ReleaseContext =
    withMetadata(CoreExecutionState.key, state)

  private[release] def executionFlags: Option[ExecutionFlags] =
    executionState.map(_.plan.flags)

  private[release] def decisionDefaults: ReleaseDecisionDefaults =
    executionState.map(_.plan.decisionDefaults).getOrElse(ReleaseDecisionDefaults.empty)

  /** Whether the compiled step sequence includes `push-changes`. Used by the
    * remote tag preflight to suppress the network probe when push will not
    * actually run (`releaseIOPolicyEnablePush := false`). Defaults to `true`
    * so legacy paths that never set the execution state preserve the
    * conservative "push is happening" behavior.
    */
  private[release] def pushConfigured: Boolean =
    executionState.fold(true)(_.pushConfigured)

  /** Mark `versions` as a tentative seed installed by
    * `validateInquireVersionsWithContext`. The marker is consumed by
    * [[clearTentativeSeeds]] at the validate→execute boundary; explicit
    * values (CLI overrides, hook-installed) leave the marker absent and
    * survive the boundary cleanup.
    */
  private[release] def markVersionsTentativelySeeded: ReleaseContext =
    withMetadata(ReleaseContext.tentativelySeededVersionsKey, ())

  /** Drop the tentative version seed installed at validate time so that
    * execute-phase hooks see the contract-mandated `None` and
    * `inquireVersions.execute` re-resolves cleanly. Also strips the State
    * mirror at `ReleaseKeys.versions` (set by [[withVersions]]) so sbt task
    * evaluations in execute-time hooks do not read the stale tentative pair.
    */
  private[release] override def clearTentativeSeeds: ReleaseContext =
    if (metadata(ReleaseContext.tentativelySeededVersionsKey).isEmpty) this
    else
      copy(
        versions = None,
        state = state.remove(ReleaseKeys.versions)
      ).withoutMetadata(ReleaseContext.tentativelySeededVersionsKey)

  override def fail: ReleaseContext                       = copy(failed = true)
  override def failWith(cause: Throwable): ReleaseContext =
    copy(failed = true, failureCause = Some(cause))
}

object ReleaseContext {

  // The publish/push execution-tracking keys now live on the shared `ReleaseCtx` companion.
  // The companion itself stays public so the case class's synthesized `apply` / `unapply`
  // remain accessible to hook and custom-plugin code.
  private[release] val tentativelySeededVersionsKey: AttributeKey[Unit] =
    AttributeKey[Unit]("releaseIOInternalCoreTentativelySeededVersions")
}
