package io.release.internal

import cats.effect.IO
import cats.effect.Ref
import io.release.ReleaseContext
import io.release.ReleaseHookIO
import io.release.ReleaseIO
import io.release.TestSupport
import io.release.steps.ReleaseSteps
import munit.CatsEffectSuite
import sbt.Project
import sbt.Setting

class ReleaseHookCompilerSpec extends CatsEffectSuite {

  test(
    "compile - match the built-in release steps when no hook or policy customization is present"
  ) {
    hookStateResource("release-hook-compiler-defaults").use { state =>
      IO {
        assertEquals(
          ReleaseHookCompiler.compile(state).map(_.name),
          ReleaseSteps.defaults.map(_.name)
        )
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
      ReleaseIO.releaseIOEnableSnapshotDependenciesCheck := false,
      ReleaseIO.releaseIOEnableRunClean                  := false,
      ReleaseIO.releaseIOEnableRunTests                  := false,
      ReleaseIO.releaseIOEnablePublish                   := false,
      ReleaseIO.releaseIOEnablePush                      := false,
      ReleaseIO.releaseIOAfterCleanCheckHooks            := Seq(
        ReleaseHookIO.action("after-clean")(_ => IO.unit)
      ),
      ReleaseIO.releaseIOBeforeVersionResolutionHooks    := Seq(
        ReleaseHookIO.action("before-version")(_ => IO.unit)
      ),
      ReleaseIO.releaseIOAfterVersionResolutionHooks     := Seq(
        ReleaseHookIO.action("after-version")(_ => IO.unit)
      ),
      ReleaseIO.releaseIOBeforeTagHooks                  := Seq(
        ReleaseHookIO.action("before-tag")(_ => IO.unit)
      ),
      ReleaseIO.releaseIOAfterTagHooks                   := Seq(
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
      val settings: Seq[Setting[?]] = Seq(
        ReleaseIO.releaseIOBeforePublishHooks := Seq(
          ReleaseHookIO(
            name = "before-publish",
            execute = ctx => observed.update(_ :+ "execute-before").as(ctx),
            validate = _ => observed.update(_ :+ "validate-before")
          )
        ),
        ReleaseIO.releaseIOAfterPublishHooks  := Seq(
          ReleaseHookIO(
            name = "after-publish",
            execute = ctx => observed.update(_ :+ "execute-after").as(ctx),
            validate = _ => observed.update(_ :+ "validate-after")
          )
        )
      )

      hookStateResource("release-hook-compiler-publish-gate", settings).use { state =>
        val publishHookSteps = ReleaseHookCompiler
          .compile(state)
          .filter(step =>
            step.name.startsWith("before-publish:") || step.name.startsWith("after-publish:")
          )
        val skippedCtx       = ReleaseContext(state = state, skipPublish = true)
        val enabledCtx       = ReleaseContext(state = state, skipPublish = false)

        def runPublishHooks(ctx: ReleaseContext): IO[Unit] =
          publishHookSteps.foldLeft(IO.unit) { (acc, step) =>
            acc *> step.validate(ctx) *> step.execute(ctx).void
          }

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

  private def hookSettingsDefaults: Seq[Setting[?]] =
    Seq(
      ReleaseIO.releaseIOEnableSnapshotDependenciesCheck := true,
      ReleaseIO.releaseIOEnableRunClean                  := true,
      ReleaseIO.releaseIOEnableRunTests                  := true,
      ReleaseIO.releaseIOEnableTagging                   := true,
      ReleaseIO.releaseIOEnablePublish                   := true,
      ReleaseIO.releaseIOEnablePush                      := true,
      ReleaseIO.releaseIOAfterCleanCheckHooks            := Seq.empty,
      ReleaseIO.releaseIOBeforeVersionResolutionHooks    := Seq.empty,
      ReleaseIO.releaseIOAfterVersionResolutionHooks     := Seq.empty,
      ReleaseIO.releaseIOBeforeReleaseVersionWriteHooks  := Seq.empty,
      ReleaseIO.releaseIOAfterReleaseVersionWriteHooks   := Seq.empty,
      ReleaseIO.releaseIOBeforeReleaseCommitHooks        := Seq.empty,
      ReleaseIO.releaseIOAfterReleaseCommitHooks         := Seq.empty,
      ReleaseIO.releaseIOBeforeTagHooks                  := Seq.empty,
      ReleaseIO.releaseIOAfterTagHooks                   := Seq.empty,
      ReleaseIO.releaseIOBeforePublishHooks              := Seq.empty,
      ReleaseIO.releaseIOAfterPublishHooks               := Seq.empty,
      ReleaseIO.releaseIOBeforeNextVersionWriteHooks     := Seq.empty,
      ReleaseIO.releaseIOAfterNextVersionWriteHooks      := Seq.empty,
      ReleaseIO.releaseIOBeforeNextCommitHooks           := Seq.empty,
      ReleaseIO.releaseIOAfterNextCommitHooks            := Seq.empty,
      ReleaseIO.releaseIOBeforePushHooks                 := Seq.empty,
      ReleaseIO.releaseIOAfterPushHooks                  := Seq.empty
    )
}
