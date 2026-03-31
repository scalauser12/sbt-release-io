package io.release.monorepo.steps

import cats.effect.IO
import cats.effect.Resource
import io.release.monorepo.MonorepoProjectFailures
import io.release.monorepo.MonorepoSpecSupport
import sbt.Def

import java.io.File

trait MonorepoPublishStepsSpecSupport {

  protected def singleProjectFixtureResource(
      prefix: String,
      rootSettings: Seq[Def.Setting[?]] = Nil,
      projectId: String = "core"
  )(projectSettings: File => Seq[Def.Setting[?]] = _ => Nil): Resource[IO, MonorepoSpecSupport.LoadedFixture] =
    MonorepoSpecSupport.loadedFixtureResource(prefix) { dir =>
      val projectBase = new File(dir, projectId)
      projectBase.mkdirs()

      Seq(
        MonorepoSpecSupport.monorepoRootProject(
          dir,
          projectIds = Seq(projectId),
          settings = rootSettings
        ),
        MonorepoSpecSupport.versionedProject(
          projectId,
          projectBase,
          settings = projectSettings(projectBase)
        )
      )
    }

  protected def twoProjectFixtureResource(
      prefix: String,
      rootSettings: Seq[Def.Setting[?]] = Nil,
      firstProjectId: String = "core",
      secondProjectId: String = "api"
  )(
      firstSettings: File => Seq[Def.Setting[?]] = _ => Nil,
      secondSettings: File => Seq[Def.Setting[?]] = _ => Nil
  ): Resource[IO, MonorepoSpecSupport.LoadedFixture] =
    MonorepoSpecSupport.loadedFixtureResource(prefix) { dir =>
      val firstBase  = new File(dir, firstProjectId)
      val secondBase = new File(dir, secondProjectId)
      firstBase.mkdirs()
      secondBase.mkdirs()

      Seq(
        MonorepoSpecSupport.monorepoRootProject(
          dir,
          projectIds = Seq(firstProjectId, secondProjectId),
          settings = rootSettings
        ),
        MonorepoSpecSupport.versionedProject(
          firstProjectId,
          firstBase,
          settings = firstSettings(firstBase)
        ),
        MonorepoSpecSupport.versionedProject(
          secondProjectId,
          secondBase,
          settings = secondSettings(secondBase)
        )
      )
    }

  protected def requireProjectFailures(
      cause: Option[Throwable]
  ): MonorepoProjectFailures =
    MonorepoSpecSupport.requireProjectFailures(cause)
}

