package io.release

import cats.effect.IO
import sbt.State
import sbtrelease.ReleasePlugin.autoImport.ReleaseStep

import scala.language.implicitConversions

/** Compatibility layer for converting sbt-release types to sbt-release-io types. */
object SbtReleaseCompat {

  /** Convert a sbt-release ReleaseStep to ReleaseStepIO. */
  implicit def releaseStepToReleaseStepIO(step: ReleaseStep): ReleaseStepIO =
    ReleaseStepIO(
      name = deriveName(step),
      action = ctx => IO {
        val newState = step.action(ctx.state)
        ctx.copy(state = newState)
      },
      check = ctx => IO {
        val newState = step.check(ctx.state)
        ctx.copy(state = newState)
      },
      enableCrossBuild = step.enableCrossBuild
    )

  /** Convert a State transformation function to ReleaseStepIO. */
  implicit def stateTransformToReleaseStepIO(f: State => State): ReleaseStepIO =
    releaseStepToReleaseStepIO(ReleaseStep(action = f))

  private def deriveName(step: ReleaseStep): String = {
    val className = step.action.getClass.getName
    val stripped = if (className.endsWith("$")) className.dropRight(1) else className
    val shortName = stripped.split('.').lastOption.getOrElse("")
    if (shortName.isEmpty) "<sbt-release step>" else shortName
  }
}
