package io.release.internal

import cats.effect.IO
import cats.effect.Ref
import io.release.ReleaseContext
import io.release.ReleaseHookIO
import io.release.ReleaseIO
import io.release.TestSupport
import io.release.steps.ReleaseSteps
import munit.CatsEffectSuite
import sbt.*
import sbt.Keys.*

import java.io.File

class ReleaseHookCompilerSpec extends CatsEffectSuite {

  test(
    "compile - match the built-in release steps when no hook or policy customization is present"
  ) {
    hookStateResource("release-hook-compiler-defaults").use { state =>
      IO {
        assertEquals(ReleaseHookCompiler.compile(state), ReleaseSteps.defaults)
      }
    }
  }

  test("compile(configuration) - match compile(state) for the same resolved hook configuration") {
    hookStateResource("release-hook-compiler-overload").use { state =>
      IO {
        assertEquals(
          ReleaseHookCompiler.compile(ReleaseHookCompiler.resolve(state)).map(_.name),
          ReleaseHookCompiler.compile(state).map(_.name)
        )
      }
    }
  }

  test("compile - apply policy flags and lifecycle hooks around the remaining built-in phases") {
    val settings: Seq[Setting[?]] = Seq(
      ReleaseIO.releaseIOPolicyEnableSnapshotDependenciesCheck := false,
      ReleaseIO.releaseIOPolicyEnableRunClean                  := false,
      ReleaseIO.releaseIOPolicyEnableRunTests                  := false,
      ReleaseIO.releaseIOPolicyEnablePublish                   := false,
      ReleaseIO.releaseIOPolicyEnablePush                      := false,
      ReleaseIO.releaseIOHooksAfterCleanCheck                  := Seq(
        ReleaseHookIO.action("after-clean")(_ => IO.unit)
      ),
      ReleaseIO.releaseIOHooksBeforeVersionResolution          := Seq(
        ReleaseHookIO.action("before-version")(_ => IO.unit)
      ),
      ReleaseIO.releaseIOHooksAfterVersionResolution           := Seq(
        ReleaseHookIO.action("after-version")(_ => IO.unit)
      ),
      ReleaseIO.releaseIOHooksBeforeTag                        := Seq(
        ReleaseHookIO.action("before-tag")(_ => IO.unit)
      ),
      ReleaseIO.releaseIOHooksAfterTag                         := Seq(
        ReleaseHookIO.action("after-tag")(_ => IO.unit)
      )
    )

    hookStateResource("release-hook-compiler-order", settings).use { state =>
      IO {
        val stepNames = ReleaseHookCompiler.compile(state).map(_.name)

        assertEquals(
          stepNames,
          Seq(
            "initialize-vcs",
            "check-clean-working-dir",
            "after-clean-check:after-clean",
            "before-version-resolution:before-version",
            "inquire-versions",
            "after-version-resolution:after-version",
            "set-release-version",
            "commit-release-version",
            "before-tag:before-tag",
            "tag-release",
            "after-tag:after-tag",
            "set-next-version",
            "commit-next-version"
          )
        )
        assert(!stepNames.exists(_.startsWith("before-publish:")))
        assert(!stepNames.exists(_.startsWith("after-publish:")))
      }
    }
  }

  test("compile - skip publish hook validation and execution when publish is skipped at runtime") {
    Ref.of[IO, List[String]](Nil).flatMap { observed =>
      val settings = publishHookSettings(observed)

      hookStateResource("release-hook-compiler-publish-gate", settings).use { state =>
        val publishHookSteps    = ReleaseHookCompiler
          .compile(state)
          .filter(step =>
            step.name.startsWith("before-publish:") || step.name.startsWith("after-publish:")
          )
        val skippedCtx          = ReleaseContext(state = state, skipPublish = true)
        val publishSkippedState =
          TestSupport.appendSessionSettings(
            state,
            Seq(publish / skip := true)
          )
        val publishSkippedCtx   = ReleaseContext(state = publishSkippedState, skipPublish = false)
        val enabledCtx          = ReleaseContext(state = state, skipPublish = false)

        for {
          _              <- runPublishHooks(publishHookSteps, skippedCtx)
          skipped        <- observed.get
          _               = assertEquals(skipped, Nil)
          _              <- runPublishHooks(publishHookSteps, publishSkippedCtx)
          publishSkipped <- observed.get
          _               = assertEquals(publishSkipped, Nil)
          _              <- runPublishHooks(publishHookSteps, enabledCtx)
          events         <- observed.get
        } yield assertEquals(
          events,
          List("validate-before", "validate-after", "execute-before", "execute-after")
        )
      }
    }
  }

  test(
    "compile - run publish hooks when aggregated publish still includes a non-skipped project"
  ) {
    Ref.of[IO, List[String]](Nil).flatMap { observed =>
      val settings = publishHookSettings(observed)

      multiProjectHookStateResource(
        "release-hook-compiler-aggregate-publish-gate",
        rootSettings = settings ++ Seq(publish / skip := true),
        childSettings = Seq(publish / skip := false)
      ).use { state =>
        val publishHookSteps = ReleaseHookCompiler
          .compile(state)
          .filter(step =>
            step.name.startsWith("before-publish:") || step.name.startsWith("after-publish:")
          )
        val ctx              = ReleaseContext(state = state, skipPublish = false)

        runPublishHooks(publishHookSteps, ctx)
          .flatMap(_ => observed.get)
          .map(events =>
            assertEquals(
              events,
              List("validate-before", "validate-after", "execute-before", "execute-after")
            )
          )
      }
    }
  }

  test("compile - publish hook execute reuses cached enabled decisions from validation") {
    Ref.of[IO, List[String]](Nil).flatMap { observed =>
      val settings = publishHookSettings(observed)

      hookStateResource("release-hook-compiler-publish-cache-enabled", settings).use { state =>
        val publishHookSteps = ReleaseHookCompiler
          .compile(state)
          .filter(step =>
            step.name.startsWith("before-publish:") || step.name.startsWith("after-publish:")
          )
        val enabledCtx       = ReleaseContext(state = state, skipPublish = false)

        for {
          validatedCtx <- validatePublishHooks(publishHookSteps, enabledCtx)
          _            <- executePublishHooks(
                            publishHookSteps,
                            validatedCtx.copy(skipPublish = true)
                          )
          events       <- observed.get
        } yield assertEquals(
          events,
          List("validate-before", "validate-after", "execute-before", "execute-after")
        )
      }
    }
  }

  test("compile - publish hook execute reuses cached skipped decisions from validation") {
    Ref.of[IO, List[String]](Nil).flatMap { observed =>
      val settings = publishHookSettings(observed)

      hookStateResource("release-hook-compiler-publish-cache-skipped", settings).use { state =>
        val publishHookSteps    = ReleaseHookCompiler
          .compile(state)
          .filter(step =>
            step.name.startsWith("before-publish:") || step.name.startsWith("after-publish:")
          )
        val publishSkippedState =
          TestSupport.appendSessionSettings(
            state,
            Seq(publish / skip := true)
          )
        val skippedCtx          = ReleaseContext(state = publishSkippedState, skipPublish = false)

        for {
          validatedCtx <- validatePublishHooks(publishHookSteps, skippedCtx)
          _            <- executePublishHooks(
                            publishHookSteps,
                            validatedCtx.withState(state)
                          )
          events       <- observed.get
        } yield assertEquals(events, Nil)
      }
    }
  }

  private def hookStateResource(
      prefix: String,
      rootSettings: Seq[Setting[?]] = Nil
  ) =
    TestSupport.tempDirResource(prefix).evalMap { dir =>
      IO.blocking(
        TestSupport.loadedState(
          dir,
          Seq(
            Project("root", dir).settings((hookSettingsDefaults ++ rootSettings)*)
          ),
          currentProjectId = Some("root")
        )
      )
    }

  private def multiProjectHookStateResource(
      prefix: String,
      rootSettings: Seq[Setting[?]],
      childSettings: Seq[Setting[?]]
  ) =
    TestSupport.tempDirResource(prefix).evalMap { dir =>
      IO.blocking {
        val childBase = new File(dir, "child")
        childBase.mkdirs()

        TestSupport.loadedState(
          dir,
          Seq(
            Project("root", dir)
              .aggregate(LocalProject("child"))
              .settings((hookSettingsDefaults ++ rootSettings)*),
            Project("child", childBase).settings(childSettings*)
          ),
          currentProjectId = Some("root")
        )
      }
    }

  private def hookSettingsDefaults: Seq[Setting[?]] =
    Seq(
      ReleaseIO.releaseIOPolicyEnableSnapshotDependenciesCheck := true,
      ReleaseIO.releaseIOPolicyEnableRunClean                  := true,
      ReleaseIO.releaseIOPolicyEnableRunTests                  := true,
      ReleaseIO.releaseIOPolicyEnableTagging                   := true,
      ReleaseIO.releaseIOPolicyEnablePublish                   := true,
      ReleaseIO.releaseIOPolicyEnablePush                      := true,
      ReleaseIO.releaseIOHooksAfterCleanCheck                  := Seq.empty,
      ReleaseIO.releaseIOHooksBeforeVersionResolution          := Seq.empty,
      ReleaseIO.releaseIOHooksAfterVersionResolution           := Seq.empty,
      ReleaseIO.releaseIOHooksBeforeReleaseVersionWrite        := Seq.empty,
      ReleaseIO.releaseIOHooksAfterReleaseVersionWrite         := Seq.empty,
      ReleaseIO.releaseIOHooksBeforeReleaseCommit              := Seq.empty,
      ReleaseIO.releaseIOHooksAfterReleaseCommit               := Seq.empty,
      ReleaseIO.releaseIOHooksBeforeTag                        := Seq.empty,
      ReleaseIO.releaseIOHooksAfterTag                         := Seq.empty,
      ReleaseIO.releaseIOHooksBeforePublish                    := Seq.empty,
      ReleaseIO.releaseIOHooksAfterPublish                     := Seq.empty,
      ReleaseIO.releaseIOHooksBeforeNextVersionWrite           := Seq.empty,
      ReleaseIO.releaseIOHooksAfterNextVersionWrite            := Seq.empty,
      ReleaseIO.releaseIOHooksBeforeNextCommit                 := Seq.empty,
      ReleaseIO.releaseIOHooksAfterNextCommit                  := Seq.empty,
      ReleaseIO.releaseIOHooksBeforePush                       := Seq.empty,
      ReleaseIO.releaseIOHooksAfterPush                        := Seq.empty
    )

  private def publishHookSettings(
      observed: Ref[IO, List[String]]
  ): Seq[Setting[?]] =
    Seq(
      ReleaseIO.releaseIOHooksBeforePublish := Seq(
        ReleaseHookIO(
          name = "before-publish",
          execute = ctx => observed.update(_ :+ "execute-before").as(ctx),
          validate = _ => observed.update(_ :+ "validate-before")
        )
      ),
      ReleaseIO.releaseIOHooksAfterPublish  := Seq(
        ReleaseHookIO(
          name = "after-publish",
          execute = ctx => observed.update(_ :+ "execute-after").as(ctx),
          validate = _ => observed.update(_ :+ "validate-after")
        )
      )
    )

  private def runPublishHooks(
      steps: Seq[CoreProcessStep],
      ctx: ReleaseContext
  ): IO[ReleaseContext] =
    validatePublishHooks(steps, ctx).flatMap(executePublishHooks(steps, _))

  private def validatePublishHooks(
      steps: Seq[CoreProcessStep],
      ctx: ReleaseContext
  ): IO[ReleaseContext] =
    steps.foldLeft(IO.pure(ctx)) { (ioCtx, step) =>
      ioCtx.flatMap(step.threadedValidation)
    }

  private def executePublishHooks(
      steps: Seq[CoreProcessStep],
      ctx: ReleaseContext
  ): IO[ReleaseContext] =
    steps.foldLeft(IO.pure(ctx)) { (ioCtx, step) =>
      ioCtx.flatMap(step.execute)
    }
}
