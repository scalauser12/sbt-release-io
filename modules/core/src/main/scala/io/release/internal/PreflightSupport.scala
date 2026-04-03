package io.release.internal

import cats.effect.IO
import io.release.ReleaseCtx

private[release] object PreflightSupport {

  sealed trait Evaluation[+A]

  object Evaluation {
    final case class Resolved[A](value: A)        extends Evaluation[A]
    final case class NotEvaluated(reason: String) extends Evaluation[Nothing]
  }

  final case class StepInventory(stepNames: Seq[String]) {
    def contains(stepName: String): Boolean = stepNames.contains(stepName)
  }

  object StepInventory {
    def fromSteps[A](steps: Seq[A])(nameOf: A => String): StepInventory =
      StepInventory(steps.map(nameOf))
  }

  def publishSummary(
      publishConfigured: Boolean,
      skipPublish: Boolean,
      skippedMessage: String
  ): String =
    CheckModeOutput.publishStatus(publishConfigured, skipPublish, skippedMessage)

  def pushSummary(pushConfigured: Boolean): String =
    CheckModeOutput.pushStatus(pushConfigured)

  def notEvaluated[A](reason: String): Evaluation[A] =
    Evaluation.NotEvaluated(reason)

  def renderEvaluation[A](
      evaluation: Evaluation[A]
  )(
      onResolved: A => String,
      onNotEvaluated: String => String = reason => s"not evaluated ($reason)"
  ): String =
    evaluation match {
      case Evaluation.Resolved(value)      => onResolved(value)
      case Evaluation.NotEvaluated(reason) => onNotEvaluated(reason)
    }

  def validatePreparedSegment[Ctx <: ReleaseCtx[Ctx]](
      logPrefix: String,
      preparedSteps: Seq[StepExecutionSupport.PreparedStep[Ctx]],
      ctx: Ctx
  ): IO[Ctx] =
    ExecutionEngine.runValidations(
      logPrefix,
      preparedSteps.map(step => ExecutionEngine.ValidationStep(step.name, step.validate)),
      ctx
    )
}
