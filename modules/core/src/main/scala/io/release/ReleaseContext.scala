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

  /** Per-iteration keys (matching `CoreLifecycle.scalaVersionKey`) for which
    * `publish-artifacts` actually executed the publish task. `None` means the publish
    * step has not yet run; an empty `Some` means it ran but every iteration skipped.
    * Used to gate `after-publish` hooks against the actual publish outcome rather than a
    * pre-publish skip evaluation that a `before-publish` hook may have rendered stale.
    */
  private[release] def publishExecutedKeys: Option[Set[String]] =
    metadata(ReleaseContext.publishExecutedKeysKey)

  private[release] def recordPublishExecuted(key: String): ReleaseContext =
    withMetadata(
      ReleaseContext.publishExecutedKeysKey,
      publishExecutedKeys.getOrElse(Set.empty) + key
    )

  private[release] def markPublishExecutionStarted: ReleaseContext =
    if (publishExecutedKeys.isDefined) this
    else withMetadata(ReleaseContext.publishExecutedKeysKey, Set.empty[String])

  /** Frozen validate-time decision for `publish-artifacts`. Captured by the publish
    * step's validation so that a hook running after validation but before publish
    * cannot flip `skipPublish` from `true` to `false` and bypass the publishTo /
    * `publish / skip` checks the validation skipped under the original decision.
    * `None` means validation has not run yet (e.g. unit-test paths that invoke
    * execute directly); execute then falls back to the live `skipPublish` value.
    */
  private[release] def publishSkipFrozen: Option[Boolean] =
    metadata(ReleaseContext.publishSkipFrozenKey)

  private[release] def freezePublishSkip(skip: Boolean): ReleaseContext =
    if (publishSkipFrozen.isDefined) this
    else withMetadata(ReleaseContext.publishSkipFrozenKey, skip)

  /** True iff `push-changes` actually pushed to the remote during this release.
    * False when the operator declined the push (CLI `default-push-answer n`,
    * `releaseIODefaultsPushAnswer := Some(false)`, non-interactive no-default,
    * interactive decline, EOF). Used to gate `after-push` hooks on the real
    * push outcome rather than a pre-push policy upper bound.
    */
  private[release] def pushExecuted: Boolean =
    metadata(ReleaseContext.pushExecutedKey).getOrElse(false)

  private[release] def markPushExecuted: ReleaseContext =
    withMetadata(ReleaseContext.pushExecutedKey, true)

  override def fail: ReleaseContext                       = copy(failed = true)
  override def failWith(cause: Throwable): ReleaseContext =
    copy(failed = true, failureCause = Some(cause))
}

object ReleaseContext {

  // Internal metadata key for the publish-execution snapshot consumed by
  // `CoreLifecycle.afterPublishNarrow`. Hidden from external consumers; the
  // companion itself stays public so the case class's synthesized `apply` /
  // `unapply` remain accessible to hook and custom-plugin code.
  private val publishExecutedKeysKey: AttributeKey[Set[String]] =
    AttributeKey[Set[String]]("releaseIOInternalCorePublishExecutedKeys")

  private val publishSkipFrozenKey: AttributeKey[Boolean] =
    AttributeKey[Boolean]("releaseIOInternalCorePublishSkipFrozen")

  private val pushExecutedKey: AttributeKey[Boolean] =
    AttributeKey[Boolean]("releaseIOInternalCorePushExecuted")
}
