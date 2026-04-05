package io.release.monorepo

import cats.effect.IO
import cats.effect.Resource
import io.release.internal.SbtRuntime
import sbt.Keys.*
import sbt.Project
import sbt.ProjectRef
import sbt.State

import java.io.File

trait MonorepoStepIOSpecSupport extends MonorepoDummyProjectSupport {

  protected val contextResource: Resource[IO, MonorepoContext] =
    MonorepoSpecSupport.dummyContextResource("monorepo-step-spec")

  protected def loadedContextResource(
      prefix: String,
      selectedProjectIds: Seq[String]
  )(projectsFor: File => Seq[Project]): Resource[IO, MonorepoContext] =
    MonorepoSpecSupport.loadedContextResource(prefix, selectedProjectIds)(projectsFor)

  protected def scalaVersionOf(state: State): IO[String] =
    IO.blocking(SbtRuntime.extracted(state).get(scalaVersion))

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

  protected def requireProjectFailures(
      cause: Option[Throwable]
  ): MonorepoProjectFailures =
    MonorepoSpecSupport.requireProjectFailures(cause)
}
