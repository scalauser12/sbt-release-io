package io.release.monorepo

import cats.effect.IO
import cats.effect.Ref
import io.release.TestSupport
import io.release.monorepo.steps.MonorepoReleaseSteps
import munit.CatsEffectSuite
import sbt.Keys.*
import sbt.Setting

import java.io.File

@scala.annotation.nowarn("cat=deprecation")
class MonorepoHookCompilerSpec extends CatsEffectSuite {

  test(
    "compile - match the built-in monorepo release steps when no hook or policy customization is present"
  ) {
    hookFixtureResource("monorepo-hook-compiler-defaults").use { fixture =>
      IO {
        assertEquals(MonorepoHookCompiler.compile(fixture.state), MonorepoReleaseSteps.defaults)
      }
    }
  }

  test("compile - use the per-project tagging step in the canonical lifecycle") {
    hookFixtureResource("monorepo-hook-compiler-tag-step").use { fixture =>
      IO {
        val tagStep = MonorepoHookCompiler
          .compile(fixture.state)
          .collectFirst {
            case step: MonorepoStepIO.PerProject if step.name == "tag-releases" =>
              step
          }
          .getOrElse(fail("Expected canonical tag-releases step"))

        assertEquals(tagStep, MonorepoReleaseSteps.tagReleasesPerProject)
      }
    }
  }

  test("compile(configuration) - match compile(state) for the same resolved hook configuration") {
    hookFixtureResource("monorepo-hook-compiler-overload").use { fixture =>
      IO {
        assertEquals(
          MonorepoHookCompiler.compile(MonorepoHookCompiler.resolve(fixture.state)).map(_.name),
          MonorepoHookCompiler.compile(fixture.state).map(_.name)
        )
      }
    }
  }

  test(
    "compile - apply monorepo policies and lifecycle hooks around the remaining built-in phases"
  ) {
    val settings: Seq[Setting[?]] = Seq(
      MonorepoReleaseIO.releaseIOMonorepoPolicyEnableSnapshotDependenciesCheck := false,
      MonorepoReleaseIO.releaseIOMonorepoPolicyEnableRunClean                  := false,
      MonorepoReleaseIO.releaseIOMonorepoPolicyEnableRunTests                  := false,
      MonorepoReleaseIO.releaseIOMonorepoPolicyEnablePublish                   := false,
      MonorepoReleaseIO.releaseIOMonorepoPolicyEnablePush                      := false,
      MonorepoReleaseIO.releaseIOMonorepoHooksAfterCleanCheck                  := Seq(
        MonorepoGlobalHookIO.action("after-clean")(_ => IO.unit)
      ),
      MonorepoReleaseIO.releaseIOMonorepoHooksBeforeSelection                  := Seq(
        MonorepoGlobalHookIO.action("before-selection")(_ => IO.unit)
      ),
      MonorepoReleaseIO.releaseIOMonorepoHooksAfterSelection                   := Seq(
        MonorepoGlobalHookIO.action("after-selection")(_ => IO.unit)
      ),
      MonorepoReleaseIO.releaseIOMonorepoHooksBeforeVersionResolution          := Seq(
        MonorepoProjectHookIO.action("before-version")((_, _) => IO.unit)
      ),
      MonorepoReleaseIO.releaseIOMonorepoHooksAfterVersionResolution           := Seq(
        MonorepoProjectHookIO.action("after-version")((_, _) => IO.unit)
      ),
      MonorepoReleaseIO.releaseIOMonorepoHooksBeforeTag                        := Seq(
        MonorepoProjectHookIO.action("before-tag")((_, _) => IO.unit)
      ),
      MonorepoReleaseIO.releaseIOMonorepoHooksAfterTag                         := Seq(
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
            "after-clean-check:after-clean",
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
        MonorepoReleaseIO.releaseIOMonorepoHooksBeforePublish := Seq(
          MonorepoProjectHookIO(
            name = "before-publish",
            execute = (ctx, _) => observed.update(_ :+ "execute-before").as(ctx),
            validate = (_, _) => observed.update(_ :+ "validate-before")
          )
        ),
        MonorepoReleaseIO.releaseIOMonorepoHooksAfterPublish  := Seq(
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

        val skippedCtx          = fixture.context(selectedProjectIds = Seq("core"), skipPublish = true)
        val publishSkippedState = TestSupport.appendSessionSettings(
          fixture.state,
          Seq(fixture.refsById("core") / publish / skip := true)
        )
        val publishSkippedCtx   = fixture
          .context(selectedProjectIds = Seq("core"), skipPublish = false)
          .withState(publishSkippedState)
        val enabledCtx          = fixture.context(selectedProjectIds = Seq("core"), skipPublish = false)
        val project             = fixture.projectInfo("core")

        def runPublishHooks(ctx: MonorepoContext): IO[Unit] =
          publishHookSteps
            .foldLeft(IO.pure(ctx)) { (ioCtx, step) =>
              ioCtx
                .flatTap(current => step.validate(current, project))
                .flatMap(step.execute(_, project))
            }
            .void

        for {
          _              <- runPublishHooks(skippedCtx)
          skipped        <- observed.get
          _               = assertEquals(skipped, Nil)
          _              <- runPublishHooks(publishSkippedCtx)
          projectSkipped <- observed.get
          _               = assertEquals(projectSkipped, Nil)
          _              <- runPublishHooks(enabledCtx)
          events         <- observed.get
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
        MonorepoSpecSupport.versionedProject(
          "core",
          coreBase,
          settings = Seq(publish / skip := false)
        )
      )
    }

  private def hookSettingsDefaults: Seq[Setting[?]] =
    Seq(
      MonorepoReleaseIO.releaseIOMonorepoPolicyEnableSnapshotDependenciesCheck := true,
      MonorepoReleaseIO.releaseIOMonorepoPolicyEnableRunClean                  := true,
      MonorepoReleaseIO.releaseIOMonorepoPolicyEnableRunTests                  := true,
      MonorepoReleaseIO.releaseIOMonorepoPolicyEnableTagging                   := true,
      MonorepoReleaseIO.releaseIOMonorepoPolicyEnablePublish                   := true,
      MonorepoReleaseIO.releaseIOMonorepoPolicyEnablePush                      := true,
      MonorepoReleaseIO.releaseIOMonorepoHooksAfterCleanCheck                  := Seq.empty,
      MonorepoReleaseIO.releaseIOMonorepoHooksBeforeSelection                  := Seq.empty,
      MonorepoReleaseIO.releaseIOMonorepoHooksAfterSelection                   := Seq.empty,
      MonorepoReleaseIO.releaseIOMonorepoHooksBeforeVersionResolution          := Seq.empty,
      MonorepoReleaseIO.releaseIOMonorepoHooksAfterVersionResolution           := Seq.empty,
      MonorepoReleaseIO.releaseIOMonorepoHooksBeforeReleaseVersionWrite        := Seq.empty,
      MonorepoReleaseIO.releaseIOMonorepoHooksAfterReleaseVersionWrite         := Seq.empty,
      MonorepoReleaseIO.releaseIOMonorepoHooksBeforeReleaseCommit              := Seq.empty,
      MonorepoReleaseIO.releaseIOMonorepoHooksAfterReleaseCommit               := Seq.empty,
      MonorepoReleaseIO.releaseIOMonorepoHooksBeforeTag                        := Seq.empty,
      MonorepoReleaseIO.releaseIOMonorepoHooksAfterTag                         := Seq.empty,
      MonorepoReleaseIO.releaseIOMonorepoHooksBeforePublish                    := Seq.empty,
      MonorepoReleaseIO.releaseIOMonorepoHooksAfterPublish                     := Seq.empty,
      MonorepoReleaseIO.releaseIOMonorepoHooksBeforeNextVersionWrite           := Seq.empty,
      MonorepoReleaseIO.releaseIOMonorepoHooksAfterNextVersionWrite            := Seq.empty,
      MonorepoReleaseIO.releaseIOMonorepoHooksBeforeNextCommit                 := Seq.empty,
      MonorepoReleaseIO.releaseIOMonorepoHooksAfterNextCommit                  := Seq.empty,
      MonorepoReleaseIO.releaseIOMonorepoHooksBeforePush                       := Seq.empty,
      MonorepoReleaseIO.releaseIOMonorepoHooksAfterPush                        := Seq.empty
    )
}
