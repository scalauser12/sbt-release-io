package io.release.monorepo

import cats.effect.IO
import cats.effect.Ref
import io.release.TestSupport
import io.release.monorepo.steps.MonorepoReleaseSteps
import munit.CatsEffectSuite
import sbt.LocalProject
import sbt.Project
import sbt.Keys.*

import java.io.File

class MonorepoStepIOWrapperSpec extends CatsEffectSuite with MonorepoStepIOSpecSupport {

  test(
    "compose - compatibility global wrapper delegates wrapped per-project validation and cross-build execution"
  ) {
    loadedContextResource("monorepo-step-compatibility-wrapper", Seq("core")) { dir =>
      val coreBase = new File(dir, "core")
      coreBase.mkdirs()

      Seq(
        Project("root", dir)
          .aggregate(LocalProject("core"))
          .settings(scalaVersion := TestSupport.CurrentScalaVersion),
        Project("core", coreBase).settings(
          scalaVersion           := TestSupport.CurrentScalaVersion,
          crossScalaVersions     := Seq(
            TestSupport.CurrentScalaVersion,
            TestSupport.alternateScalaVersion
          )
        )
      )
    }.use { baseCtx =>
      Ref.of[IO, List[String]](Nil).flatMap { observed =>
        val wrappedStep = MonorepoStepIO.PerProject(
          name = "wrapped-tag-release",
          validate = (ctx, project) =>
            scalaVersionOf(ctx.state)
              .flatMap(version => observed.update(_ :+ s"validate:${project.name}:$version")),
          execute = (ctx, project) =>
            scalaVersionOf(ctx.state)
              .flatMap(version => observed.update(_ :+ s"execute:${project.name}:$version"))
              .as(ctx),
          enableCrossBuild = true
        )
        val wrapper     = MonorepoReleaseSteps.compatibilityGlobalStep("tag-releases", wrappedStep)
        val ctx         = MonorepoSpecSupport.withPlan(
          baseCtx,
          MonorepoSpecSupport.releasePlan(
            selectionMode = SelectionMode.AllChanged,
            flags = MonorepoSpecSupport.defaultFlags.copy(crossBuild = true)
          )
        )

        MonorepoStepIO.compose(Seq(wrapper))(ctx).flatMap { result =>
          for {
            events          <- observed.get
            restoredVersion <- scalaVersionOf(result.state)
          } yield {
            assertEquals(
              events,
              List(
                s"validate:core:${TestSupport.CurrentScalaVersion}",
                s"validate:core:${TestSupport.alternateScalaVersion}",
                s"execute:core:${TestSupport.CurrentScalaVersion}",
                s"execute:core:${TestSupport.alternateScalaVersion}"
              )
            )
            assertEquals(restoredVersion, TestSupport.CurrentScalaVersion)
          }
        }
      }
    }
  }
}
