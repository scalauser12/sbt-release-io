package io.release

import io.release.internal.ExecutionFlags
import io.release.internal.ReleaseDecisionDefaults
import io.release.vcs.Vcs
import sbt.AttributeKey
import sbt.AttributeMap
import sbt.State

/** Common interface for immutable release contexts threaded through steps.
  *
  * Both [[ReleaseContext]] (single-project) and the monorepo `MonorepoContext`
  * implement this trait, enabling shared utilities like [[VcsOps]]
  * to operate polymorphically on either context type.
  *
  * Public fields carry step-visible release data; package-private runtime accessors
  * expose startup-only execution metadata to the built-in implementation. This keeps
  * user metadata and internal planning data separate while preserving immutable threading.
 */
private[release] trait ReleaseCtx {
  def state: State
  def vcs: Option[Vcs]
  def interactive: Boolean
  def failed: Boolean
  def failureCause: Option[Throwable]

  // ── Metadata ──────────────────────────────────────────────────────────

  def metadataBag: AttributeMap
  def metadata[A](key: AttributeKey[A]): Option[A] = metadataBag.get(key)

  // ── Internal runtime metadata ────────────────────────────────────────

  private[release] def executionFlags: Option[ExecutionFlags]
  private[release] def decisionDefaults: ReleaseDecisionDefaults

  private[release] def useDefaults: Boolean =
    executionFlags.exists(_.useDefaults)
}

private[release] trait ReleaseCtxOps[C <: ReleaseCtx] {
  def withState(ctx: C, state: State): C
  def withVcs(ctx: C, vcs: Vcs): C
  def fail(ctx: C): C
  def failWith(ctx: C, cause: Throwable): C
  def withMetadata[A](ctx: C, key: AttributeKey[A], value: A): C
  def withoutMetadata[A](ctx: C, key: AttributeKey[A]): C
}

private[release] object ReleaseCtxOps {

  def apply[C <: ReleaseCtx](implicit ops: ReleaseCtxOps[C]): ReleaseCtxOps[C] = ops

  object syntax {

    implicit final class ReleaseCtxSyntax[C <: ReleaseCtx](private val ctx: C) extends AnyVal {

      def withState(state: State)(implicit ops: ReleaseCtxOps[C]): C =
        ops.withState(ctx, state)

      def withVcs(vcs: Vcs)(implicit ops: ReleaseCtxOps[C]): C =
        ops.withVcs(ctx, vcs)

      def fail(implicit ops: ReleaseCtxOps[C]): C =
        ops.fail(ctx)

      def failWith(cause: Throwable)(implicit ops: ReleaseCtxOps[C]): C =
        ops.failWith(ctx, cause)

      def withMetadata[A](key: AttributeKey[A], value: A)(implicit ops: ReleaseCtxOps[C]): C =
        ops.withMetadata(ctx, key, value)

      def withoutMetadata[A](key: AttributeKey[A])(implicit ops: ReleaseCtxOps[C]): C =
        ops.withoutMetadata(ctx, key)
    }
  }
}
