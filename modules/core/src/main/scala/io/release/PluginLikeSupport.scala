package io.release

import scala.language.implicitConversions

/** Shared resource-aware step composition helpers used by both [[ReleasePluginIOLike]]
  * and the monorepo `MonorepoReleasePluginLike`.
  *
  * @tparam StepType the concrete step type (`ReleaseStepIO` or `MonorepoStepIO`)
  * @tparam T        the resource type acquired once per release
  */
private[release] trait PluginLikeSupport[StepType, T] {

  /** Extract the step name for error messages. */
  protected def stepName(step: StepType): String

  protected implicit def liftStep(step: StepType): T => StepType =
    (_: T) => step

  protected def liftSteps(steps: Seq[StepType]): Seq[T => StepType] =
    steps.map(liftStep)

  /** Find a step by name in a sequence, raising `IllegalArgumentException` on missing. */
  protected def findStepIndex(defaults: Seq[StepType], name: String): Int = {
    val idx = defaults.indexWhere(s => stepName(s) == name)
    if (idx < 0)
      throw new IllegalArgumentException(
        s"Step '$name' not found in defaults. " +
          s"Available: ${defaults.map(stepName).mkString(", ")}"
      )
    idx
  }

  /** Read defaults, insert extra steps after the named step. */
  protected def insertAfter(defaults: Seq[StepType], afterStep: String)(
      extraSteps: Seq[T => StepType]
  ): Seq[T => StepType] = {
    val (before, after) = defaults.splitAt(findStepIndex(defaults, afterStep) + 1)
    liftSteps(before) ++ extraSteps ++ liftSteps(after)
  }

  /** Read defaults, insert extra steps before the named step. */
  protected def insertBefore(defaults: Seq[StepType], beforeStep: String)(
      extraSteps: Seq[T => StepType]
  ): Seq[T => StepType] = {
    val (before, after) = defaults.splitAt(findStepIndex(defaults, beforeStep))
    liftSteps(before) ++ extraSteps ++ liftSteps(after)
  }
}
