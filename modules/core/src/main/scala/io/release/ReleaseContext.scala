package io.release

import io.release.internal.CoreExecutionState
import io.release.internal.ExecutionFlags
import io.release.internal.ReleaseDecisionDefaults
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

  def withState(s: State): ReleaseContext = copy(state = s)

  /** Set the release and next version pair, updating both the context field
    * and the sbt State attribute so that sbt tasks can read the versions.
    */
  def withVersions(release: String, next: String): ReleaseContext =
    copy(
      versions = Some((release, next)),
      state = state.put(ReleaseKeys.versions, (release, next))
    )

  def withVcs(v: Vcs): ReleaseContext =
    copy(vcs = Some(v))

  def withMetadata[A](key: AttributeKey[A], value: A): ReleaseContext =
    copy(metadataBag = metadataBag.put(key, value))

  def withoutMetadata[A](key: AttributeKey[A]): ReleaseContext =
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

  def fail: ReleaseContext                       = copy(failed = true)
  def failWith(cause: Throwable): ReleaseContext = copy(failed = true, failureCause = Some(cause))
}

object ReleaseContext {

  implicit val releaseContextOps: ReleaseCtxOps[ReleaseContext] =
    new ReleaseCtxOps[ReleaseContext] {
      override def withState(ctx: ReleaseContext, state: State): ReleaseContext =
        ctx.withState(state)

      override def withVcs(ctx: ReleaseContext, vcs: Vcs): ReleaseContext =
        ctx.withVcs(vcs)

      override def fail(ctx: ReleaseContext): ReleaseContext =
        ctx.fail

      override def failWith(ctx: ReleaseContext, cause: Throwable): ReleaseContext =
        ctx.failWith(cause)

      override def withMetadata[A](
          ctx: ReleaseContext,
          key: AttributeKey[A],
          value: A
      ): ReleaseContext =
        ctx.withMetadata(key, value)

      override def withoutMetadata[A](
          ctx: ReleaseContext,
          key: AttributeKey[A]
      ): ReleaseContext =
        ctx.withoutMetadata(key)
    }
}
