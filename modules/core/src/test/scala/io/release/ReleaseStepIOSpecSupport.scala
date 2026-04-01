package io.release

import cats.effect.IO
import cats.effect.Resource
import io.release.internal.SbtRuntime
import sbt.Keys
import sbt.Project
import sbt.State
import sbt.inputKey
import sbt.taskKey

import java.io.File
import java.nio.charset.StandardCharsets

trait ReleaseStepIOSpecSupport {

  protected val contextResource: Resource[IO, ReleaseContext] =
    ReleaseTestSupport.dummyContextResource("sbt-release-io-compose-spec")

  protected def loadedContextResource(
      prefix: String,
      configure: Project => Project
  ): Resource[IO, ReleaseContext] =
    ReleaseTestSupport.loadedContextResource(prefix, currentProjectId = Some("root")) { dir =>
      Seq(configure(Project("root", dir)))
    }

  protected def loadedContextWithProjectsResource(
      prefix: String
  )(projectsFor: File => Seq[Project]): Resource[IO, ReleaseContext] =
    ReleaseTestSupport.loadedContextResource(prefix, currentProjectId = Some("root"))(projectsFor)

  protected def scalaVersionOf(state: State): IO[String] =
    IO.blocking(SbtRuntime.extracted(state).get(Keys.scalaVersion))

  protected def readFile(file: File): IO[String] =
    IO.blocking(sbt.IO.read(file, StandardCharsets.UTF_8))

  protected val stateUpdateTask      = taskKey[String]("stateUpdateTask")
  protected val stateUpdateInputTask = inputKey[String]("stateUpdateInputTask")
}
