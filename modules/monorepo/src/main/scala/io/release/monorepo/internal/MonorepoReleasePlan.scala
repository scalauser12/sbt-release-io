package io.release.monorepo.internal

import cats.effect.IO
import io.release.runtime.ExecutionFlags
import io.release.runtime.ReleaseDecisionDefaults
import io.release.runtime.ReleaseLogPrefixes
import sbt.State

/** How project selection is determined for a monorepo release. */
private[monorepo] sealed trait SelectionMode

private[monorepo] object SelectionMode {
  case object ExplicitSelection extends SelectionMode
  case object AllChanged        extends SelectionMode
  case object DetectChanges     extends SelectionMode
}

/** Stable startup inputs captured once at monorepo command start. */
private[monorepo] final case class MonorepoReleasePlan(
    flags: ExecutionFlags,
    selectionMode: SelectionMode,
    selectedNames: Seq[String],
    releaseVersionOverrides: Map[String, String],
    nextVersionOverrides: Map[String, String],
    decisionDefaults: ReleaseDecisionDefaults,
    commandName: String = "releaseIOMonorepo"
)

private[monorepo] object MonorepoReleasePlan {

  val metadataKey: sbt.AttributeKey[MonorepoReleasePlan] =
    sbt.AttributeKey[MonorepoReleasePlan](
      "releaseIOInternalMonorepoExecutionState"
    )

  // ── Planning types ─────────────────────────────────────────────────

  final case class Inputs(
      flags: ExecutionFlags,
      allChanged: Boolean,
      selectedNames: Seq[String],
      releaseVersionPairs: Seq[(String, String)],
      nextVersionPairs: Seq[(String, String)],
      decisionDefaults: ReleaseDecisionDefaults = ReleaseDecisionDefaults.empty,
      commandName: String = "releaseIOMonorepo"
  )

  // ── Build & validate ──────────────────────────────────────────────

  def build(state: State, inputs: Inputs): IO[Either[State, MonorepoReleasePlan]] =
    validateOverrideInputs(inputs) match {
      case Left(message) =>
        IO.blocking {
          state.log.error(s"${ReleaseLogPrefixes.Monorepo} $message")
          Left(state.fail)
        }
      case Right(plan)   => IO.pure(Right(plan))
    }

  def validateOverrideInputs(inputs: Inputs): Either[String, MonorepoReleasePlan] = {
    val releaseVersionPairs     = inputs.releaseVersionPairs
    val nextVersionPairs        = inputs.nextVersionPairs
    val releaseVersionOverrides = releaseVersionPairs.toMap
    val nextVersionOverrides    = nextVersionPairs.toMap

    def failWhen(condition: Boolean, msg: => String): Either[String, Unit] =
      if (condition) Left(msg) else Right(())

    for {
      _ <- failWhen(
             releaseVersionPairs.exists { case (p, v) => p.isEmpty || v.isEmpty },
             "Invalid release-version format. Expected project=version"
           )
      _ <- failWhen(
             nextVersionPairs.exists { case (p, v) => p.isEmpty || v.isEmpty },
             "Invalid next-version format. Expected project=version"
           )
      _ <- failWhen(
             releaseVersionPairs.groupBy(_._1).exists(_._2.length > 1),
             "Duplicate per-project release-version overrides: " +
               releaseVersionPairs.groupBy(_._1).filter(_._2.length > 1).keys.mkString(", ")
           )
      _ <- failWhen(
             nextVersionPairs.groupBy(_._1).exists(_._2.length > 1),
             "Duplicate per-project next-version overrides: " +
               nextVersionPairs.groupBy(_._1).filter(_._2.length > 1).keys.mkString(", ")
           )
      _ <- failWhen(
             inputs.allChanged && inputs.selectedNames.nonEmpty,
             "Cannot combine 'all-changed' with explicit project selection. " +
               "Either use 'all-changed' alone or specify projects explicitly."
           )
    } yield {
      val selectionMode =
        if (inputs.selectedNames.nonEmpty) SelectionMode.ExplicitSelection
        else if (inputs.allChanged) SelectionMode.AllChanged
        else SelectionMode.DetectChanges

      MonorepoReleasePlan(
        flags = inputs.flags,
        selectionMode = selectionMode,
        selectedNames = inputs.selectedNames,
        releaseVersionOverrides = releaseVersionOverrides,
        nextVersionOverrides = nextVersionOverrides,
        decisionDefaults = inputs.decisionDefaults,
        commandName = inputs.commandName
      )
    }
  }
}
