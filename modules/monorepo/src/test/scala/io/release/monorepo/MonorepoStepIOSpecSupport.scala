package io.release.monorepo

import cats.effect.IO
import cats.effect.Resource
import io.release.monorepo.internal.*
import io.release.monorepo.internal.MonorepoStepAliases.AnyStep
import io.release.runtime.engine.BuiltInStepRole
import io.release.runtime.engine.ProcessStep
import io.release.runtime.sbt.SbtRuntime
import sbt.Keys.*
import sbt.Project
import sbt.ProjectRef
import sbt.State

import java.io.File

trait MonorepoStepIOSpecSupport extends MonorepoDummyProjectSupport {

  private val selectionBoundary: AnyStep = ProcessStep.Single[MonorepoContext](
    name = MonorepoComposer.SelectionBoundary,
    execute = ctx => IO.pure(ctx),
    roles = Set(BuiltInStepRole.SelectionBoundary)
  )

  protected val contextResource: Resource[IO, MonorepoContext] =
    MonorepoSpecSupport.dummyContextResource("monorepo-step-spec")

  protected def loadedContextResource(
      prefix: String,
      selectedProjectIds: Seq[String]
  )(projectsFor: File => Seq[Project]): Resource[IO, MonorepoContext] =
    MonorepoSpecSupport.loadedContextResource(prefix, selectedProjectIds)(projectsFor)

  protected def composeCanonical(
      steps: Seq[AnyStep],
      crossBuild: Boolean = false
  )(ctx: MonorepoContext): IO[MonorepoContext] = {
    val canonicalSteps =
      if (steps.exists(_.hasRole(BuiltInStepRole.SelectionBoundary))) steps
      else selectionBoundary +: steps

    MonorepoComposer.compose(canonicalSteps, crossBuild)(ctx)
  }

  protected def scalaVersionOf(state: State): IO[String] =
    IO.blocking(SbtRuntime.extracted(state).get(scalaVersion))

  protected def scalaVersionOf(state: State, ref: ProjectRef): IO[String] =
    IO.blocking(SbtRuntime.extracted(state).get(ref / scalaVersion))

  protected def scopedScalaVersionOf(state: State): IO[Option[String]] =
    IO.blocking {
      val extracted = SbtRuntime.extracted(state)
      (extracted.currentRef / scalaVersion)
        .get(extracted.structure.data)
        .orElse((sbt.GlobalScope / scalaVersion).get(extracted.structure.data))
    }

  protected def projectScalaVersionOf(state: State, ref: ProjectRef): IO[Option[String]] =
    IO.blocking {
      val extracted = SbtRuntime.extracted(state)
      (ref / scalaVersion)
        .get(extracted.structure.data)
        .orElse((sbt.GlobalScope / scalaVersion).get(extracted.structure.data))
    }

  protected def appendCurrentScalaVersion(file: File, state: State): IO[Unit] =
    scalaVersionOf(state).flatMap(version => IO.blocking(sbt.IO.append(file, s"$version\n")))

  protected def appendCurrentScalaVersion(file: File, state: State, ref: ProjectRef): IO[Unit] =
    scalaVersionOf(state, ref).flatMap(version => IO.blocking(sbt.IO.append(file, s"$version\n")))

  protected def requireProjectFailures(
      cause: Option[Throwable]
  ): MonorepoProjectFailures =
    MonorepoSpecSupport.requireProjectFailures(cause)
}
