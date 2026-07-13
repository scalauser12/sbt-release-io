package io.release.monorepo.internal

import sbt.{internal as _, *}

/** Internal publish-validation and execution state for one monorepo release cycle. */
private[monorepo] final case class MonorepoPublishState(
    validation: MonorepoPublishState.Validation,
    probes: Map[
      MonorepoPublishState.PublishIteration,
      MonorepoPublishState.PublishValidationProbe
    ],
    execution: Option[
      Map[MonorepoPublishState.PublishIteration, MonorepoPublishState.AttemptOutcome]
    ]
) {
  import MonorepoPublishState.*

  def validationCompleted: Boolean = validation.isInstanceOf[Finalized]

  def validationInitialized: Boolean = validation != Uninitialized

  def validationSnapshotEstablished: Boolean = validation match {
    case Collecting(_) | Finalized(Some(_))               => true
    case Uninitialized | ChecksDisabled | Finalized(None) => false
  }

  def validatedEligibility(iteration: PublishIteration): Option[Boolean] =
    validation match {
      case Collecting(eligibility)                          => eligibility.get(iteration)
      case Finalized(Some(eligibility))                     => eligibility.get(iteration)
      case Uninitialized | ChecksDisabled | Finalized(None) => None
    }

  def validationProbe(input: PublishIteration): Option[PublishValidationProbe] =
    probes.get(input)

  def validatedHookSource(entry: PublishIteration): Option[PublishIteration] =
    probes.valuesIterator.find(_.entry == entry).map(_.postSkip)

  def validatedGateDecision(entry: PublishIteration): Option[Boolean] =
    probes.valuesIterator.find(_.entry == entry).map(probe => !probe.publishSkipped)

  def initializeChecks(enabled: Boolean): MonorepoPublishState =
    validation match {
      case Uninitialized if enabled =>
        copy(validation = Collecting(Map.empty))
      case Uninitialized            =>
        copy(validation = ChecksDisabled)
      case _                        => this
    }

  def recordEligibility(
      iteration: PublishIteration,
      eligible: Boolean
  ): MonorepoPublishState = {
    def updated(decisions: Map[PublishIteration, Boolean]): Map[PublishIteration, Boolean] = {
      val upperBound = decisions.get(iteration).fold(eligible)(_ && eligible)
      decisions + (iteration -> upperBound)
    }

    validation match {
      case Collecting(decisions)            => copy(validation = Collecting(updated(decisions)))
      case Finalized(Some(decisions))       => copy(validation = Finalized(Some(updated(decisions))))
      case Uninitialized                    => copy(validation = Collecting(updated(Map.empty)))
      case ChecksDisabled | Finalized(None) => this
    }
  }

  def recordProbe(probe: PublishValidationProbe): MonorepoPublishState = {
    val withoutInput = probes - probe.input
    val conflict     = withoutInput.valuesIterator
      .filter(_.entry == probe.entry)
      .filter(existing =>
        existing.postSkip != probe.postSkip ||
          existing.publishSkipped != probe.publishSkipped
      )
      .toSeq
      .sortBy(_.input.gateKey)
      .headOption

    conflict.foreach { existing =>
      val observations = Seq(existing, probe).sortBy(_.input.gateKey).map { observation =>
        s"input '${observation.input.gateKey}' -> " +
          s"post-skip '${observation.postSkip.gateKey}', " +
          s"publishSkipped=${observation.publishSkipped}"
      }
      throw new IllegalStateException(
        s"Conflicting publish validation probes share entry '${probe.entry.gateKey}': " +
          observations.mkString("; ")
      )
    }

    copy(probes = withoutInput + (probe.input -> probe))
  }

  def completeTargetValidation(input: PublishIteration): MonorepoPublishState = {
    val probe = validationProbe(input).getOrElse(
      throw new IllegalStateException(
        s"Publish validation probe missing for '${input.gateKey}'"
      )
    )
    recordProbe(probe.copy(targetProgress = PublishTargetProgress.Validated))
  }

  def beginValidationBatch: MonorepoPublishState = {
    val reset =
      if (validationCompleted) copy(validation = Uninitialized, probes = Map.empty) else this
    reset.copy(execution = None)
  }

  def resetCompletedValidation: MonorepoPublishState =
    if (validationCompleted)
      copy(validation = Uninitialized, probes = Map.empty)
    else this

  def finalizeValidation: MonorepoPublishState = {
    val finalized = validation match {
      case Collecting(decisions) => Finalized(Some(decisions))
      case ChecksDisabled        => Finalized(None)
      case Uninitialized         => Finalized(None)
      case value: Finalized      => value
    }
    copy(validation = finalized)
  }

  def beginExecutionBatch: MonorepoPublishState =
    copy(execution = Some(Map.empty))

  def recordAttempt(iteration: PublishIteration): MonorepoPublishState =
    copy(execution = Some(execution.getOrElse(Map.empty) + (iteration -> Attempted)))

  def recordSuccess(
      attempt: PublishIteration,
      aliases: Set[PublishIteration]
  ): MonorepoPublishState =
    copy(execution = Some(execution.getOrElse(Map.empty) + (attempt -> Succeeded(aliases))))

  def currentExecutionOutcome(live: PublishIteration): Option[AfterPublishOutcome] =
    execution.map(resolveExecutionOutcome(_, live))

  private def resolveExecutionOutcome(
      outcomes: Map[PublishIteration, AttemptOutcome],
      live: PublishIteration
  ): AfterPublishOutcome =
    outcomes.get(live) match {
      case Some(_: Succeeded) => AfterPublishOutcome(live, succeeded = true)
      case Some(Attempted)    => AfterPublishOutcome(live, succeeded = false)
      case None               =>
        val owners = outcomes.iterator.collect {
          case (attempt, Succeeded(aliases)) if aliases.contains(live) => attempt
        }.toSeq
        owners match {
          case Seq(owner) => AfterPublishOutcome(owner, succeeded = true)
          case _          => AfterPublishOutcome(live, succeeded = false)
        }
    }
}

private[monorepo] object MonorepoPublishState {

  sealed trait Validation
  case object Uninitialized  extends Validation
  case object ChecksDisabled extends Validation
  final case class Collecting(
      eligibility: Map[PublishIteration, Boolean]
  ) extends Validation
  final case class Finalized(
      eligibility: Option[Map[PublishIteration, Boolean]]
  ) extends Validation

  final case class PublishIteration(
      ref: ProjectRef,
      scalaVersion: String
  ) {
    def gateKey: String =
      s"${ref.build.toASCIIString}#${ref.project}:$scalaVersion"
  }

  sealed trait PublishTargetProgress
  object PublishTargetProgress {
    case object NotRequired                extends PublishTargetProgress
    final case class Pending(state: State) extends PublishTargetProgress
    case object Validated                  extends PublishTargetProgress
  }

  final case class PublishValidationProbe(
      input: PublishIteration,
      entry: PublishIteration,
      postSkip: PublishIteration,
      publishSkipped: Boolean,
      targetProgress: PublishTargetProgress
  )

  sealed trait AttemptOutcome
  case object Attempted                                      extends AttemptOutcome
  final case class Succeeded(aliases: Set[PublishIteration]) extends AttemptOutcome

  final case class AfterPublishOutcome(
      gateIteration: PublishIteration,
      succeeded: Boolean
  )

  val empty: MonorepoPublishState =
    MonorepoPublishState(
      validation = Uninitialized,
      probes = Map.empty,
      execution = None
    )

  private[monorepo] val metadataKey: AttributeKey[MonorepoPublishState] =
    AttributeKey[MonorepoPublishState]("releaseIOInternalMonorepoPublishState")
}
