package io.release.monorepo

import io.release.steps.StepHelpers.errorMessage

/** Per-project failure record captured during a monorepo release step.
  * Aggregated into [[MonorepoProjectFailures]] when project-level failures are propagated
  * to the global [[MonorepoContext.failed]] flag.
  */
final case class MonorepoProjectFailure(
    projectName: String,
    cause: Option[Throwable]
)

/** Aggregate exception wrapping one or more per-project failures.
  * Chains the first project's cause via `initCause` and adds the rest as suppressed exceptions.
  */
final class MonorepoProjectFailures(
    val failures: Seq[MonorepoProjectFailure]
) extends IllegalStateException(
      MonorepoProjectFailures.message(failures)
    ) {

  private val causes = failures.flatMap(_.cause)
  causes.headOption.foreach(initCause)
  causes.drop(1).foreach(addSuppressed)
}

object MonorepoProjectFailures {

  private def message(failures: Seq[MonorepoProjectFailure]): String = {
    val rendered = failures.map(f => s"${f.projectName}: ${f.cause.fold("failed")(errorMessage)}")
    s"Per-project release failures:\n${rendered.map("  " + _).mkString("\n")}"
  }
}
