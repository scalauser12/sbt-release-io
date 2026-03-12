package io.release.internal

import cats.effect.IO
import sbt.{internal => _, *}

/** Builds and stores the typed execution plan for the core release command. */
private[release] object CoreReleasePlanner {

  final case class Inputs(
      useDefaults: Boolean,
      skipTests: Boolean,
      skipPublish: Boolean,
      interactive: Boolean,
      crossBuild: Boolean,
      releaseVersionOverride: Option[String],
      nextVersionOverride: Option[String],
      tagDefault: Option[String]
  )

  def build(inputs: Inputs): CoreReleasePlan = {
    val flags = ExecutionFlags(
      useDefaults = inputs.useDefaults,
      skipTests = inputs.skipTests,
      skipPublish = inputs.skipPublish,
      interactive = inputs.interactive,
      crossBuild = inputs.crossBuild
    )
    CoreReleasePlan(
      flags = flags,
      releaseVersionOverride = inputs.releaseVersionOverride,
      nextVersionOverride = inputs.nextVersionOverride,
      tagDefault = inputs.tagDefault
    )
  }

  def attach(state: State, plan: CoreReleasePlan): State =
    state
      .put(InternalKeys.coreReleasePlan, plan)
      .put(InternalKeys.executionFlags, plan.flags)

  def current(state: State): Option[CoreReleasePlan] =
    state.get(InternalKeys.coreReleasePlan)

  def require(state: State): IO[CoreReleasePlan] =
    IO.fromOption(current(state))(new IllegalStateException("Core release plan not initialized"))
}
