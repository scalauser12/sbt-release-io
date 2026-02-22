package io.release

import cats.effect.IO
import sbt.State
import sbtrelease.ReleasePlugin.autoImport.ReleaseStep

import scala.language.implicitConversions

/** Compatibility layer for converting sbt-release types to sbt-release-io types. */
object SbtReleaseCompat {

  private def lift(f: State => State): ReleaseContext => IO[ReleaseContext] =
    ctx => IO.blocking(ctx.copy(state = f(ctx.state)))

  /** Convert a sbt-release ReleaseStep to ReleaseStepIO. */
  implicit def releaseStepToReleaseStepIO(step: ReleaseStep): ReleaseStepIO =
    ReleaseStepIO(
      name = deriveName(step),
      action = lift(step.action),
      check = lift(step.check),
      enableCrossBuild = step.enableCrossBuild
    )

  /** Convert a State transformation function to ReleaseStepIO. */
  implicit def stateTransformToReleaseStepIO(f: State => State): ReleaseStepIO =
    releaseStepToReleaseStepIO(ReleaseStep(action = f))

  private def deriveName(step: ReleaseStep): String = {
    val className = step.action.getClass.getName
    val stripped  = className.stripSuffix("$")
    val shortName = stripped.split('.').lastOption.getOrElse("")
    if (shortName.isEmpty) "<sbt-release step>" else shortName
  }
}
