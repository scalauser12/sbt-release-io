package io.release.runtime

import _root_.sbt.AttributeKey
import _root_.sbt.AttributeMap
import _root_.sbt.State
import io.release.vcs.Vcs

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
  type Self <: ReleaseCtx

  def state: State
  def vcs: Option[Vcs]
  def interactive: Boolean
  def failed: Boolean
  def failureCause: Option[Throwable]

  // ── Mutation ──────────────────────────────────────────────────────────

  def withState(state: State): Self
  def withVcs(vcs: Vcs): Self
  def fail: Self
  def failWith(cause: Throwable): Self
  def withMetadata[A](key: AttributeKey[A], value: A): Self
  def withoutMetadata[A](key: AttributeKey[A]): Self

  // ── Metadata ──────────────────────────────────────────────────────────

  def metadataBag: AttributeMap
  def metadata[A](key: AttributeKey[A]): Option[A] = metadataBag.get(key)

  // ── Internal runtime metadata ────────────────────────────────────────

  private[release] def executionFlags: Option[ExecutionFlags]
  private[release] def decisionDefaults: ReleaseDecisionDefaults

  private[release] def useDefaults: Boolean =
    executionFlags.exists(_.useDefaults)
}
