package io.release

import io.release.internal.ReleaseDecisionDefaults
import io.release.internal.ExecutionFlags
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
  *
  * @tparam Self the concrete context type (F-bounded polymorphism)
  */
private[release] trait ReleaseCtx[Self] {
  def state: State
  def vcs: Option[Vcs]
  def interactive: Boolean
  def failed: Boolean
  def failureCause: Option[Throwable]
  def withState(s: State): Self
  def withVcs(v: Vcs): Self
  def fail: Self
  def failWith(cause: Throwable): Self

  // ── Metadata ──────────────────────────────────────────────────────────

  def metadataBag: AttributeMap
  def metadata[A](key: AttributeKey[A]): Option[A] = metadataBag.get(key)
  def withMetadata[A](key: AttributeKey[A], value: A): Self
  def withoutMetadata[A](key: AttributeKey[A]): Self

  // ── Internal runtime metadata ────────────────────────────────────────

  private[release] def executionFlags: Option[ExecutionFlags]
  private[release] def decisionDefaults: ReleaseDecisionDefaults

  private[release] def useDefaults: Boolean =
    executionFlags.exists(_.useDefaults)
}
