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

  /** Drop validate-time tentative version seeds before execute starts.
    *
    * `validateInquireVersionsWithContext` (core/monorepo) seeds tentative
    * non-prompting version values into the validated context so that
    * `precondition` hooks at `afterVersionResolution` and later phases observe
    * versions during `releaseIO check`. Without this hook, that seed would
    * flow into the execute phase and either bypass interactive prompts
    * (monorepo `inquireVersions.execute` short-circuits on
    * `project.resolvedVersions.isDefined`) or violate the
    * `beforeVersionResolution` execute-hook contract (those hooks must see
    * `releaseVersion == None`). [[io.release.runtime.engine.ExecutionEngine]]
    * calls this at the validate→execute boundary in both orchestration modes.
    *
    * Default is a no-op; overrides in [[io.release.ReleaseContext]] and
    * `MonorepoContext` consult a metadata marker set by the seeders so only
    * tentatively-seeded values are dropped. Explicit values from CLI overrides
    * or hooks are preserved.
    */
  private[release] def clearTentativeSeeds: Self = this.asInstanceOf[Self]
}
