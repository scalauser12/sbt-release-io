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

  /** `this`, typed as the concrete `Self`. Each subtype implements it as `this`. Lets the
    * idempotency branches in the metadata helpers below return `self` (typed `Self`) rather
    * than `this` (typed as the supertype `ReleaseCtx`, which would not match the `Self`
    * return type). A `self: Self =>` self-type cannot express the path-dependent `this.Self`,
    * so this abstract accessor is used instead.
    */
  protected def self: Self

  def state: State
  def vcs: Option[Vcs]
  def interactive: Boolean
  def skipPublish: Boolean
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

  // ── Publish / push execution tracking ────────────────────────────────
  // Shared by core and monorepo: both record which publish iterations actually ran, freeze
  // the validate-time publish-skip decision, and flag whether push happened. A single backing
  // key per accessor is safe because a context instance is only ever one concrete type.

  /** Per-iteration keys for which `publish-artifacts` actually executed the publish task.
    * `None` means the publish step has not run yet; an empty `Some` means it ran but every
    * iteration skipped. Used to gate `after-publish` hooks on the real publish outcome.
    */
  private[release] def publishExecutedKeys: Option[Set[String]] =
    metadata(ReleaseCtx.publishExecutedKeysKey)

  private[release] def recordPublishExecuted(key: String): Self =
    withMetadata(
      ReleaseCtx.publishExecutedKeysKey,
      publishExecutedKeys.getOrElse(Set.empty) + key
    )

  private[release] def markPublishExecutionStarted: Self =
    if (publishExecutedKeys.isDefined) self
    else withMetadata(ReleaseCtx.publishExecutedKeysKey, Set.empty[String])

  /** Frozen validate-time decision for `publish-artifacts`, so a hook running after validation
    * but before publish cannot flip `skipPublish` from `true` to `false` and bypass the
    * publishTo / `publish / skip` checks validation skipped. `None` means validation has not
    * run yet; execute then falls back to the live `skipPublish` value.
    */
  private[release] def publishSkipFrozen: Option[Boolean] =
    metadata(ReleaseCtx.publishSkipFrozenKey)

  private[release] def freezePublishSkip(skip: Boolean): Self =
    if (publishSkipFrozen.isDefined) self
    else withMetadata(ReleaseCtx.publishSkipFrozenKey, skip)

  /** True iff `push-changes` actually pushed to the remote during this release. False when the
    * operator declined the push. Used to gate `after-push` hooks on the real push outcome.
    */
  private[release] def pushExecuted: Boolean =
    metadata(ReleaseCtx.pushExecutedKey).getOrElse(false)

  private[release] def markPushExecuted: Self =
    withMetadata(ReleaseCtx.pushExecutedKey, true)

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
    * calls this at the validate→execute boundary in both orchestration modes
    * (skipped when validation already failed).
    *
    * Abstract on purpose — every concrete `ReleaseCtx` must decide what
    * "tentative seed" means for its shape. The two production subtypes
    * ([[io.release.ReleaseContext]] and `MonorepoContext`) consult a metadata
    * marker set by their respective seeders so only tentatively-seeded values
    * are dropped; explicit values from CLI overrides or hooks survive.
    */
  private[release] def clearTentativeSeeds: Self
}

private[release] object ReleaseCtx {

  // Backing keys for the shared publish/push execution-tracking accessors. Declared once here
  // rather than per-context: any instance is exactly one concrete type, so a single key cannot
  // collide between core and monorepo.
  private[release] val publishExecutedKeysKey: AttributeKey[Set[String]] =
    AttributeKey[Set[String]]("releaseIOInternalPublishExecutedKeys")

  private[release] val publishSkipFrozenKey: AttributeKey[Boolean] =
    AttributeKey[Boolean]("releaseIOInternalPublishSkipFrozen")

  private[release] val pushExecutedKey: AttributeKey[Boolean] =
    AttributeKey[Boolean]("releaseIOInternalPushExecuted")
}
