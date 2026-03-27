package io.release.monorepo

import cats.effect.IO
import cats.effect.Ref
import io.release.TestSupport
import io.release.monorepo.steps.MonorepoReleaseSteps
import munit.CatsEffectSuite
import sbt.Project
import sbt.Setting

import java.io.File

class MonorepoHookCompilerSpec extends CatsEffectSuite {

  test(
    "compile - match the built-in monorepo release steps when no hook or policy customization is present"
  ) {
    hookFixtureResource("monorepo-hook-compiler-defaults").use { fixture =>
      IO {
        assertEquals(
          MonorepoHookCompiler.compile(fixture.state).map(_.name),
          MonorepoReleaseSteps.defaults.map(_.name)
        )
      }
    }
  }

  test(
    "compile - apply monorepo policies and lifecycle hooks around the remaining built-in phases"
  ) {
    val settings: Seq[Setting[?]] = Seq(
      MonorepoReleaseIO.releaseIOMonorepoEnableSnapshotDependenciesCheck := false,
      MonorepoReleaseIO.releaseIOMonorepoEnableRunClean                  := false,
      MonorepoReleaseIO.releaseIOMonorepoEnableRunTests                  := false,
      MonorepoReleaseIO.releaseIOMonorepoEnablePublish                   := false,
      MonorepoReleaseIO.releaseIOMonorepoEnablePush                      := false,
      MonorepoReleaseIO.releaseIOMonorepoBeforeSelectionHooks            := Seq(
        MonorepoGlobalHookIO.action("before-selection")(_ => IO.unit)
      ),
      MonorepoReleaseIO.releaseIOMonorepoAfterSelectionHooks             := Seq(
        MonorepoGlobalHookIO.action("after-selection")(_ => IO.unit)
      ),
      MonorepoReleaseIO.releaseIOMonorepoBeforeVersionResolutionHooks    := Seq(
        MonorepoProjectHookIO.action("before-version")((_, _) => IO.unit)
      ),
      MonorepoReleaseIO.releaseIOMonorepoAfterVersionResolutionHooks     := Seq(
        MonorepoProjectHookIO.action("after-version")((_, _) => IO.unit)
      ),
      MonorepoReleaseIO.releaseIOMonorepoBeforeTagHooks                  := Seq(
        MonorepoProjectHookIO.action("before-tag")((_, _) => IO.unit)
      ),
      MonorepoReleaseIO.releaseIOMonorepoAfterTagHooks                   := Seq(
        MonorepoProjectHookIO.action("after-tag")((_, _) => IO.unit)
      )
    )

    hookFixtureResource("monorepo-hook-compiler-order", settings).use { fixture =>
      IO {
        val stepNames = MonorepoHookCompiler.compile(fixture.state).map(_.name)

        assertEquals(
          stepNames,
          Seq(
            "initialize-vcs",
            "check-clean-working-dir",
            "resolve-release-order",
            "before-selection:before-selection",
            "detect-or-select-projects",
            "after-selection:after-selection",
            "before-version-resolution:before-version",
            "inquire-versions",
            "after-version-resolution:after-version",
            "set-release-version",
            "commit-release-versions",
            "before-tag:before-tag",
            "tag-releases",
            "after-tag:after-tag",
            "set-next-version",
            "commit-next-versions"
          )
        )
        assert(!stepNames.exists(_.startsWith("before-publish:")))
        assert(!stepNames.exists(_.startsWith("after-publish:")))
      }
    }
  }

  test("compile - skip publish hook validation and execution when publish is skipped at runtime") {
    Ref.of[IO, List[String]](Nil).flatMap { observed =>
      val settings: Seq[Setting[?]] = Seq(
        MonorepoReleaseIO.releaseIOMonorepoBeforePublishHooks := Seq(
          MonorepoProjectHookIO(
            name = "before-publish",
            execute = (ctx, _) => observed.update(_ :+ "execute-before").as(ctx),
            validate = (_, _) => observed.update(_ :+ "validate-before")
          )
        ),
        MonorepoReleaseIO.releaseIOMonorepoAfterPublishHooks  := Seq(
          MonorepoProjectHookIO(
            name = "after-publish",
            execute = (ctx, _) => observed.update(_ :+ "execute-after").as(ctx),
            validate = (_, _) => observed.update(_ :+ "validate-after")
          )
        )
      )

      hookFixtureResource("monorepo-hook-compiler-publish-gate", settings).use { fixture =>
        val publishHookSteps = MonorepoHookCompiler
          .compile(fixture.state)
          .collect {
            case step: MonorepoStepIO.PerProject
                if step.name
                  .startsWith("before-publish:") || step.name.startsWith("after-publish:") =>
              step
          }

        val skippedCtx = fixture.context(selectedProjectIds = Seq("core"), skipPublish = true)
        val enabledCtx = fixture.context(selectedProjectIds = Seq("core"), skipPublish = false)
        val project    = fixture.projectInfo("core")

        def runPublishHooks(ctx: MonorepoContext): IO[Unit] =
          publishHookSteps
            .foldLeft(IO.pure(ctx)) { (ioCtx, step) =>
              ioCtx
                .flatTap(current => step.validate(current, project))
                .flatMap(step.execute(_, project))
            }
            .void

        for {
          _       <- runPublishHooks(skippedCtx)
          skipped <- observed.get
          _        = assertEquals(skipped, Nil)
          _       <- runPublishHooks(enabledCtx)
          events  <- observed.get
        } yield assertEquals(
          events,
          List("validate-before", "execute-before", "validate-after", "execute-after")
        )
      }
    }
  }

  private def hookFixtureResource(
      prefix: String,
      rootSettings: Seq[Setting[?]] = Nil
  ) =
    MonorepoSpecSupport.loadedFixtureResource(prefix) { dir =>
      val coreBase = new File(dir, "core")
      coreBase.mkdirs()

      sbt.IO.write(new File(dir, "version.sbt"), """version := "0.1.0-SNAPSHOT"""" + "\n")
      sbt.IO.write(new File(coreBase, "version.sbt"), """version := "0.1.0-SNAPSHOT"""" + "\n")

      TestSupport.initGitRepo(dir)
      TestSupport.commitAll(dir, "Initial commit")

      Seq(
        MonorepoSpecSupport.monorepoRootProject(
          dir,
          projectIds = Seq("core"),
          settings = hookSettingsDefaults ++ rootSettings
        ),
        MonorepoSpecSupport.versionedProject("core", coreBase)
      )
    }

  private def hookSettingsDefaults: Seq[Setting[?]] =
    Seq(
      MonorepoReleaseIO.releaseIOMonorepoEnableSnapshotDependenciesCheck := true,
      MonorepoReleaseIO.releaseIOMonorepoEnableRunClean                  := true,
      MonorepoReleaseIO.releaseIOMonorepoEnableRunTests                  := true,
      MonorepoReleaseIO.releaseIOMonorepoEnableTagging                   := true,
      MonorepoReleaseIO.releaseIOMonorepoEnablePublish                   := true,
      MonorepoReleaseIO.releaseIOMonorepoEnablePush                      := true,
      MonorepoReleaseIO.releaseIOMonorepoBeforeSelectionHooks            := Seq.empty,
      MonorepoReleaseIO.releaseIOMonorepoAfterSelectionHooks             := Seq.empty,
      MonorepoReleaseIO.releaseIOMonorepoBeforeVersionResolutionHooks    := Seq.empty,
      MonorepoReleaseIO.releaseIOMonorepoAfterVersionResolutionHooks     := Seq.empty,
      MonorepoReleaseIO.releaseIOMonorepoBeforeReleaseVersionWriteHooks  := Seq.empty,
      MonorepoReleaseIO.releaseIOMonorepoAfterReleaseVersionWriteHooks   := Seq.empty,
      MonorepoReleaseIO.releaseIOMonorepoBeforeReleaseCommitHooks        := Seq.empty,
      MonorepoReleaseIO.releaseIOMonorepoAfterReleaseCommitHooks         := Seq.empty,
      MonorepoReleaseIO.releaseIOMonorepoBeforeTagHooks                  := Seq.empty,
      MonorepoReleaseIO.releaseIOMonorepoAfterTagHooks                   := Seq.empty,
      MonorepoReleaseIO.releaseIOMonorepoBeforePublishHooks              := Seq.empty,
      MonorepoReleaseIO.releaseIOMonorepoAfterPublishHooks               := Seq.empty,
      MonorepoReleaseIO.releaseIOMonorepoBeforeNextVersionWriteHooks     := Seq.empty,
      MonorepoReleaseIO.releaseIOMonorepoAfterNextVersionWriteHooks      := Seq.empty,
      MonorepoReleaseIO.releaseIOMonorepoBeforeNextCommitHooks           := Seq.empty,
      MonorepoReleaseIO.releaseIOMonorepoAfterNextCommitHooks            := Seq.empty,
      MonorepoReleaseIO.releaseIOMonorepoBeforePushHooks                 := Seq.empty,
      MonorepoReleaseIO.releaseIOMonorepoAfterPushHooks                  := Seq.empty
    )
}
