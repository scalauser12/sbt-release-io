package io.release.core.internal

import sbt.{internal as _, *}

/** Internal publish-validation state for one core release cycle.
  *
  * Core publishes an aggregate batch at a time. Checks-enabled validation therefore records
  * both the aggregate cross-build key and every project/Scala identity covered by that batch.
  * Unchecked batches intentionally record only the active policy and cross-build key, with no
  * target decisions. A checks-enabled snapshot is authoritative: execute may further suppress a
  * validated target, but it must never enable a target whose publish checks were skipped.
  */
private[release] final case class CorePublishState(
    batches: Map[String, CorePublishState.PublishValidationBatch]
) {
  import CorePublishState.*

  def hasChecksEnabledBatch: Boolean = batches.values.exists(_.checksEnabled)

  def batch(key: String): Option[PublishValidationBatch] = batches.get(key)

  def recordBatch(batch: PublishValidationBatch): CorePublishState =
    copy(batches = batches + (batch.key -> batch))

  def completeTargetValidation(key: String): CorePublishState =
    batch(key).fold(this)(current => recordBatch(current.completeTargetValidation))
}

private[release] object CorePublishState {

  final case class PublishIteration(
      batchKey: String,
      ref: ProjectRef,
      scalaVersion: String
  ) {
    def display: String = s"${ref.project}:$scalaVersion"
  }

  final case class TargetDecision(
      iteration: PublishIteration,
      skipped: Boolean
  )

  sealed trait PublishTargetProgress
  object PublishTargetProgress {
    case object NotRequired                extends PublishTargetProgress
    final case class Pending(state: State) extends PublishTargetProgress
    case object Validated                  extends PublishTargetProgress
  }

  final case class PublishValidationBatch(
      key: String,
      checksEnabled: Boolean,
      decisions: Vector[TargetDecision],
      targetProgress: PublishTargetProgress
  ) {
    def hookDecision: Boolean = decisions.exists(!_.skipped)

    def decisionFor(iteration: PublishIteration): Option[TargetDecision] =
      decisions.find(_.iteration == iteration)

    def completeTargetValidation: PublishValidationBatch =
      copy(targetProgress = PublishTargetProgress.Validated)
  }

  val empty: CorePublishState =
    CorePublishState(batches = Map.empty)

  private[release] val metadataKey: AttributeKey[CorePublishState] =
    AttributeKey[CorePublishState]("releaseIOInternalCorePublishState")
}
