package io.release.core.internal

import cats.effect.IO
import cats.effect.Ref
import io.release.ReleaseContext
import io.release.ReleaseHookIO
import io.release.ReleasePluginIO
import io.release.TestSupport
import io.release.core.internal.CoreStepAliases.Step
import io.release.core.internal.steps.ReleaseSteps
import munit.CatsEffectSuite
import sbt.*
import sbt.Keys.*

import java.io.File
import java.nio.file.Files
import java.nio.file.Path

class CoreLifecycleCompilationSpec extends CatsEffectSuite {

  test(
    "compile - match the built-in release steps when no hook or policy customization is present"
  ) {
    hookStateResource("release-hook-compiler-defaults").use { state =>
      compileLifecycle(state).map { steps =>
        assertEquals(steps, ReleaseSteps.defaults)
      }
    }
  }

  test("production sources no longer reference removed hook or command wrappers") {
    hookStateResource("release-hook-compiler-overload").use { state =>
      compileLifecycle(state).map { steps =>
        val commandExecution = Files.readString(
          repoPath(
            "modules/core/src/main/scala/io/release/core/internal/CoreCommandExecution.scala"
          )
        )

        assertEquals(steps.map(_.name), ReleaseSteps.defaults.map(_.name))
        assert(
          !Files.exists(
            repoPath("modules/core/src/main/scala/io/release/internal/ReleaseHookCompiler.scala")
          )
        )
        assert(
          !Files.exists(
            repoPath("modules/core/src/main/scala/io/release/internal/SharedCommandKernel.scala")
          )
        )
        assert(!commandExecution.contains("ReleaseHookCompiler"))
        assert(!commandExecution.contains("SharedCommandKernel"))
      }
    }
  }

  test("resolve - read lifecycle policy and hook settings from state") {
    val settings: Seq[Setting[?]] = Seq(
      ReleasePluginIO.autoImport.releaseIOPolicyEnableRunTests := false,
      ReleasePluginIO.autoImport.releaseIOPolicyEnablePublish  := false,
      ReleasePluginIO.autoImport.releaseIOHooksBeforeTag       := Seq(
        ReleaseHookIO.action("before-tag")(_ => IO.unit)
      ),
      ReleasePluginIO.autoImport.releaseIOHooksAfterNextCommit := Seq(
        ReleaseHookIO.action("after-next-commit")(_ => IO.unit)
      )
    )

    hookStateResource("release-hook-compiler-resolve", settings).use { state =>
      IO {
        val config = CoreHookConfiguration.resolve(state)

        assert(!config.enableRunTests)
        assert(!config.enablePublish)
        assertEquals(config.beforeTagHooks.map(_.name), Seq("before-tag"))
        assertEquals(config.afterNextCommitHooks.map(_.name), Seq("after-next-commit"))
      }
    }
  }

  test("resolve - generated lifecycle defaults produce the empty hook configuration") {
    hookStateResource("release-hook-compiler-generated-defaults").use { state =>
      IO {
        assertEquals(CoreHookConfiguration.resolve(state), CoreHookConfiguration.empty)
      }
    }
  }

  test("compile - apply policy flags and lifecycle hooks around the remaining built-in phases") {
    val settings: Seq[Setting[?]] = Seq(
      ReleasePluginIO.autoImport.releaseIOPolicyEnableSnapshotDependenciesCheck := false,
      ReleasePluginIO.autoImport.releaseIOPolicyEnableRunClean                  := false,
      ReleasePluginIO.autoImport.releaseIOPolicyEnableRunTests                  := false,
      ReleasePluginIO.autoImport.releaseIOPolicyEnablePublish                   := false,
      ReleasePluginIO.autoImport.releaseIOPolicyEnablePush                      := false,
      ReleasePluginIO.autoImport.releaseIOHooksAfterCleanCheck                  := Seq(
        ReleaseHookIO.action("after-clean")(_ => IO.unit)
      ),
      ReleasePluginIO.autoImport.releaseIOHooksBeforeVersionResolution          := Seq(
        ReleaseHookIO.action("before-version")(_ => IO.unit)
      ),
      ReleasePluginIO.autoImport.releaseIOHooksAfterVersionResolution           := Seq(
        ReleaseHookIO.action("after-version")(_ => IO.unit)
      ),
      ReleasePluginIO.autoImport.releaseIOHooksBeforeTag                        := Seq(
        ReleaseHookIO.action("before-tag")(_ => IO.unit)
      ),
      ReleasePluginIO.autoImport.releaseIOHooksAfterTag                         := Seq(
        ReleaseHookIO.action("after-tag")(_ => IO.unit)
      )
    )

    hookStateResource("release-hook-compiler-order", settings).use { state =>
      compileLifecycle(state).map { steps =>
        val stepNames = steps.map(_.name)

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
        val skippedCtx          = ReleaseContext(state = state, skipPublish = true)
        val publishSkippedState =
          TestSupport.appendSessionSettings(
            state,
            Seq(publish / skip := true)
          )
        val publishSkippedCtx   = ReleaseContext(state = publishSkippedState, skipPublish = false)
        val enabledCtx          = ReleaseContext(state = state, skipPublish = false)

        for {
          steps           <- compileLifecycle(state)
          publishHookSteps = steps.filter(step =>
                               step.name.startsWith("before-publish:") ||
                                 step.name.startsWith("after-publish:")
                             )
          _               <- runPublishHooks(publishHookSteps, skippedCtx)
          skipped         <- observed.get
          _                = assertEquals(skipped, Nil)
          _               <- observed.set(Nil)
          _               <- runPublishHooks(publishHookSteps, publishSkippedCtx)
          publishSkipped  <- observed.get
          _                = assertEquals(publishSkipped, Nil)
          _               <- observed.set(Nil)
          _               <- runPublishHooks(publishHookSteps, enabledCtx)
          events          <- observed.get
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
        val ctx = ReleaseContext(state = state, skipPublish = false)

        for {
          steps           <- compileLifecycle(state)
          publishHookSteps = steps.filter(step =>
                               step.name.startsWith("before-publish:") ||
                                 step.name.startsWith("after-publish:")
                             )
          _               <- runPublishHooks(publishHookSteps, ctx)
          events          <- observed.get
        } yield assertEquals(
          events,
          List("validate-before", "validate-after", "execute-before", "execute-after")
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
    CoreLifecycle.configDefaultSettings

  private def compileLifecycle(state: State): IO[Seq[Step]] =
    CoreLifecycle.compile(CoreHookConfiguration.resolve(state))

  private def repoPath(relative: String): Path = {
    @scala.annotation.tailrec
    def loop(path: Path): Path =
      if (path == null) sys.error("Could not locate repository root")
      else if (Files.exists(path.resolve("build.sbt")) && Files.exists(path.resolve("modules")))
        path
      else loop(path.getParent)

    loop(Path.of("").toAbsolutePath.normalize).resolve(relative)
  }

  private def publishHookSettings(
      observed: Ref[IO, List[String]]
  ): Seq[Setting[?]] =
    Seq(
      ReleasePluginIO.autoImport.releaseIOHooksBeforePublish := Seq(
        ReleaseHookIO(
          name = "before-publish",
          execute = ctx => observed.update(_ :+ "execute-before").as(ctx),
          validate = _ => observed.update(_ :+ "validate-before")
        )
      ),
      ReleasePluginIO.autoImport.releaseIOHooksAfterPublish  := Seq(
        ReleaseHookIO(
          name = "after-publish",
          execute = ctx => observed.update(_ :+ "execute-after").as(ctx),
          validate = _ => observed.update(_ :+ "validate-after")
        )
      )
    )

  private def runPublishHooks(
      steps: Seq[Step],
      ctx: ReleaseContext
  ): IO[ReleaseContext] =
    validatePublishHooks(steps, ctx).flatMap(executePublishHooks(steps, _))

  private def validatePublishHooks(
      steps: Seq[Step],
      ctx: ReleaseContext
  ): IO[ReleaseContext] =
    steps.foldLeft(IO.pure(ctx)) { (ioCtx, step) =>
      ioCtx.flatMap(step.validate)
    }

  private def executePublishHooks(
      steps: Seq[Step],
      ctx: ReleaseContext
  ): IO[ReleaseContext] =
    steps.foldLeft(IO.pure(ctx)) { (ioCtx, step) =>
      ioCtx.flatMap(step.execute)
    }
}
