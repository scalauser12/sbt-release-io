package io.release.monorepo

import cats.effect.IO
import cats.effect.Ref
import io.release.TestSupport
import io.release.monorepo.internal.*
import io.release.monorepo.internal.MonorepoStepAliases.AnyStep
import io.release.monorepo.internal.MonorepoStepAliases.ProjectStep
import io.release.monorepo.internal.steps.MonorepoPublishSteps
import io.release.monorepo.internal.steps.MonorepoReleaseSteps
import io.release.runtime.engine.BuiltInStepRole
import io.release.runtime.engine.ProcessStep
import munit.CatsEffectSuite
import sbt.Keys.*
import sbt.Setting

import java.io.File
import java.nio.file.Files
import java.nio.file.Path

class MonorepoLifecycleCompilationSpec extends CatsEffectSuite {

  test(
    "compile - match the built-in monorepo release steps when no hook or policy customization is present"
  ) {
    hookFixtureResource("monorepo-hook-compiler-defaults").use { fixture =>
      compileLifecycle(fixture.state).map { steps =>
        assertEquals(steps, MonorepoLifecycle.defaults)
      }
    }
  }

  test("compile - use the per-project tagging step in the canonical lifecycle") {
    hookFixtureResource("monorepo-hook-compiler-tag-step").use { fixture =>
      compileLifecycle(fixture.state).map { steps =>
        val tagStep = steps
          .flatMap(asProjectStep)
          .find(_.hasRole(BuiltInStepRole.TagRelease))
          .getOrElse(fail("Expected canonical tag-releases step"))

        assertEquals(tagStep, MonorepoReleaseSteps.tagReleasesPerProject)
      }
    }
  }

  test("production sources no longer reference thin monorepo hook compiler") {
    hookFixtureResource("monorepo-hook-compiler-overload").use { fixture =>
      compileLifecycle(fixture.state).flatMap { steps =>
        IO.blocking(
          Files.readString(
            repoPath(
              "modules/monorepo/src/main/scala/io/release/monorepo/internal/MonorepoCommandExecution.scala"
            )
          )
        ).map { commandExecution =>
          assertEquals(
            steps.map(_.name),
            MonorepoLifecycle.defaults.map(_.name)
          )
          assert(
            !Files.exists(
              repoPath(
                "modules/monorepo/src/main/scala/io/release/monorepo/MonorepoHookCompiler.scala"
              )
            )
          )
          assert(!commandExecution.contains("MonorepoHookCompiler"))
          assert(!commandExecution.contains("SharedCommandKernel"))
        }
      }
    }
  }

  test("resolve - read monorepo lifecycle policy and hook settings from state") {
    val settings: Seq[Setting[?]] = Seq(
      MonorepoReleasePlugin.autoImport.releaseIOMonorepoPolicyEnableRunTests       := false,
      MonorepoReleasePlugin.autoImport.releaseIOMonorepoPolicyEnablePublish        := false,
      MonorepoReleasePlugin.autoImport.releaseIOMonorepoHooksBeforeSelection       := Seq(
        MonorepoGlobalHookIO.sideEffect("before-selection")(_ => IO.unit)
      ),
      MonorepoReleasePlugin.autoImport.releaseIOMonorepoHooksAfterNextVersionWrite := Seq(
        MonorepoProjectHookIO.sideEffect("after-next-version")((_, _) => IO.unit)
      )
    )

    hookFixtureResource("monorepo-hook-compiler-resolve", settings).use { fixture =>
      IO {
        val config = MonorepoHookConfiguration.resolve(fixture.state)

        assert(!config.enableRunTests)
        assert(!config.enablePublish)
        assertEquals(config.beforeSelectionHooks.map(_.name), Seq("before-selection"))
        assertEquals(config.afterNextVersionWriteHooks.map(_.name), Seq("after-next-version"))
      }
    }
  }

  test("resolve - generated monorepo lifecycle defaults produce the empty hook configuration") {
    hookFixtureResource("monorepo-hook-compiler-generated-defaults").use { fixture =>
      IO {
        assertEquals(
          MonorepoHookConfiguration.resolve(fixture.state),
          MonorepoHookConfiguration.empty
        )
      }
    }
  }

  test(
    "compile - apply monorepo policies and lifecycle hooks around the remaining built-in phases"
  ) {
    val settings: Seq[Setting[?]] = Seq(
      MonorepoReleasePlugin.autoImport.releaseIOMonorepoPolicyEnableSnapshotDependenciesCheck := false,
      MonorepoReleasePlugin.autoImport.releaseIOMonorepoPolicyEnableRunClean                  := false,
      MonorepoReleasePlugin.autoImport.releaseIOMonorepoPolicyEnableRunTests                  := false,
      MonorepoReleasePlugin.autoImport.releaseIOMonorepoPolicyEnablePublish                   := false,
      MonorepoReleasePlugin.autoImport.releaseIOMonorepoPolicyEnablePush                      := false,
      MonorepoReleasePlugin.autoImport.releaseIOMonorepoHooksAfterCleanCheck                  := Seq(
        MonorepoGlobalHookIO.sideEffect("after-clean")(_ => IO.unit)
      ),
      MonorepoReleasePlugin.autoImport.releaseIOMonorepoHooksBeforeSelection                  := Seq(
        MonorepoGlobalHookIO.sideEffect("before-selection")(_ => IO.unit)
      ),
      MonorepoReleasePlugin.autoImport.releaseIOMonorepoHooksAfterSelection                   := Seq(
        MonorepoGlobalHookIO.sideEffect("after-selection")(_ => IO.unit)
      ),
      MonorepoReleasePlugin.autoImport.releaseIOMonorepoHooksBeforeVersionResolution          := Seq(
        MonorepoProjectHookIO.sideEffect("before-version")((_, _) => IO.unit)
      ),
      MonorepoReleasePlugin.autoImport.releaseIOMonorepoHooksAfterVersionResolution           := Seq(
        MonorepoProjectHookIO.sideEffect("after-version")((_, _) => IO.unit)
      ),
      MonorepoReleasePlugin.autoImport.releaseIOMonorepoHooksBeforeTag                        := Seq(
        MonorepoProjectHookIO.sideEffect("before-tag")((_, _) => IO.unit)
      ),
      MonorepoReleasePlugin.autoImport.releaseIOMonorepoHooksAfterTag                         := Seq(
        MonorepoProjectHookIO.sideEffect("after-tag")((_, _) => IO.unit)
      )
    )

    hookFixtureResource("monorepo-hook-compiler-order", settings).use { fixture =>
      compileLifecycle(fixture.state).map { steps =>
        val stepNames = steps.map(_.name)

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
      val settings = publishHookSettings(observed)

      hookFixtureResource("monorepo-hook-compiler-publish-gate", settings).use { fixture =>
        val skippedCtx          = fixture.context(selectedProjectIds = Seq("core"), skipPublish = true)
        val publishSkippedState = TestSupport.appendSessionSettings(
          fixture.state,
          Seq(fixture.refsById("core") / publish / skip := true)
        )
        val publishSkippedCtx   = fixture
          .context(selectedProjectIds = Seq("core"), skipPublish = false)
          .withState(publishSkippedState)
        val baseEnabledCtx      =
          fixture.context(selectedProjectIds = Seq("core"), skipPublish = false)
        val project             = fixture.projectInfo("core")
        // After-publish hooks fire only when `publish-artifacts` actually
        // executed for the project; in the live flow that step records the
        // gate key on the context. Simulate the recorded outcome here so the
        // hook gate sees a real publish.
        val publishedKey        = MonorepoPublishSteps.publishGateKey(baseEnabledCtx, project)
        val enabledCtx          = baseEnabledCtx.recordPublishExecuted(publishedKey)

        compileLifecycle(fixture.state).flatMap { steps =>
          val publishHookSteps = publishProjectHooksOnly(steps)
          for {
            _              <- runPublishHooks(publishHookSteps, skippedCtx, project)
            skipped        <- observed.get
            _               = assertEquals(skipped, Nil)
            _              <- runPublishHooks(publishHookSteps, publishSkippedCtx, project)
            projectSkipped <- observed.get
            _               = assertEquals(projectSkipped, Nil)
            _              <- runPublishHooks(publishHookSteps, enabledCtx, project)
            events         <- observed.get
          } yield assertEquals(
            events,
            List("validate-before", "validate-after", "execute-before", "execute-after")
          )
        }
      }
    }
  }

  test(
    "compile - keep validate-time gate as upper bound for after-publish even if publish later runs"
  ) {
    Ref.of[IO, List[String]](Nil).flatMap { observed =>
      val settings = publishHookSettings(observed)

      hookFixtureResource("monorepo-hook-compiler-publish-gate-upper-bound", settings).use {
        fixture =>
          val publishSkippedState = TestSupport.appendSessionSettings(
            fixture.state,
            Seq(fixture.refsById("core") / publish / skip := true)
          )
          val baseValidateCtx     = fixture
            .context(selectedProjectIds = Seq("core"), skipPublish = false)
            .withState(publishSkippedState)
          val project             = fixture.projectInfo("core")
          // Simulate the late-execute flip: validate sees `publish / skip := true`
          // (so validate-time gate is false), but at execute time a
          // `before-publish` hook flipped it back, `publish-artifacts` ran, and
          // recorded the project's gate key. The frozen validate-time decision
          // must still skip after-publish to preserve the validate-before-execute
          // contract — recording the published key alone cannot fire the hook.
          val publishedKey        = MonorepoPublishSteps.publishGateKey(baseValidateCtx, project)
          val executeCtx          = baseValidateCtx.recordPublishExecuted(publishedKey)

          compileLifecycle(fixture.state).flatMap { steps =>
            val publishHookSteps = publishProjectHooksOnly(steps)
            for {
              _      <- validatePublishHooks(publishHookSteps, baseValidateCtx, project)
              _      <- executePublishHooks(publishHookSteps, executeCtx, project)
              events <- observed.get
            } yield assertEquals(events, Nil)
          }
      }
    }
  }

  test("compile - distinguish publish hook decisions by project identity and scalaVersion") {
    Ref.of[IO, List[String]](Nil).flatMap { observed =>
      val settings = publishHookSettings(observed)

      hookFixtureResource("monorepo-hook-compiler-publish-gate-key", settings).use { fixture =>
        val baseProject    =
          fixture.projectInfo(
            "core",
            versions = Some("0.1.0" -> "0.2.0-SNAPSHOT"),
            tagName = Some("core/v0.1.0")
          )
        val mutatedProject =
          baseProject.copy(
            versions = Some("1.0.0" -> "1.1.0-SNAPSHOT"),
            tagName = Some("core/v1.0.0")
          )
        val coreRef        = fixture.refsById("core")

        for {
          scala212State  <- stateWithProjectScalaVersion(fixture.state, coreRef, "2.12.21")
          scala3State    <- stateWithProjectScalaVersion(fixture.state, coreRef, "3.8.1")
          steps          <- compileLifecycle(fixture.state)
          publishHooks    = publishProjectHooksOnly(steps)
          validate212Ctx  = fixture
                              .context(selectedProjectIds = Seq("core"), skipPublish = false)
                              .withState(scala212State)
                              .withProjects(Seq(baseProject))
          validate3Ctx    = fixture
                              .context(selectedProjectIds = Seq("core"), skipPublish = true)
                              .withState(scala3State)
                              .withProjects(Seq(baseProject))
          // Simulate publishArtifacts having run for the 2.12.21 iteration only
          // (the 3.8.1 iteration's frozen `before-publish` decision was false,
          // so its publish task and per-iteration after-publish should both
          // skip).
          published212Key = MonorepoPublishSteps.publishGateKey(validate212Ctx, mutatedProject)
          execute212Ctx   = fixture
                              .context(selectedProjectIds = Seq("core"), skipPublish = false)
                              .withState(scala212State)
                              .withProjects(Seq(mutatedProject))
                              .recordPublishExecuted(published212Key)
          execute3Ctx     = fixture
                              .context(selectedProjectIds = Seq("core"), skipPublish = false)
                              .withState(scala3State)
                              .withProjects(Seq(mutatedProject))
                              .recordPublishExecuted(published212Key)
          _              <- validatePublishHooks(publishHooks, validate212Ctx, baseProject)
          _              <- validatePublishHooks(publishHooks, validate3Ctx, baseProject)
          _              <- executePublishHooks(publishHooks, execute212Ctx, mutatedProject)
          _              <- executePublishHooks(publishHooks, execute3Ctx, mutatedProject)
          events         <- observed.get
        } yield assertEquals(
          events,
          List("validate-before", "validate-after", "execute-before", "execute-after")
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
    MonorepoLifecycle.configDefaultSettings

  private def compileLifecycle(
      state: sbt.State
  ): IO[Seq[AnyStep]] =
    MonorepoLifecycle.compile(MonorepoHookConfiguration.resolve(state))

  private def publishProjectHooksOnly(steps: Seq[AnyStep]): Seq[ProjectStep] =
    steps
      .flatMap(asProjectStep)
      .filter(p => p.name.startsWith("before-publish:") || p.name.startsWith("after-publish:"))

  private def asProjectStep(step: AnyStep): Option[ProjectStep] =
    ProcessStep.fold[MonorepoContext, ProjectReleaseInfo, Option[ProjectStep]](step)(
      (_: ProcessStep.Single[MonorepoContext]) => None,
      Some(_)
    )

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
      MonorepoReleasePlugin.autoImport.releaseIOMonorepoHooksBeforePublish := Seq(
        MonorepoProjectHookIO(
          name = "before-publish",
          execute = (ctx, _) => observed.update(_ :+ "execute-before").as(ctx),
          validate = (_, _) => observed.update(_ :+ "validate-before")
        )
      ),
      MonorepoReleasePlugin.autoImport.releaseIOMonorepoHooksAfterPublish  := Seq(
        MonorepoProjectHookIO(
          name = "after-publish",
          execute = (ctx, _) => observed.update(_ :+ "execute-after").as(ctx),
          validate = (_, _) => observed.update(_ :+ "validate-after")
        )
      )
    )

  private def stateWithProjectScalaVersion(
      state: sbt.State,
      ref: sbt.ProjectRef,
      value: String
  ): IO[sbt.State] =
    IO.blocking(
      TestSupport.appendSessionSettings(
        state,
        Seq(ref / scalaVersion := value)
      )
    )

  private def runPublishHooks(
      steps: Seq[ProjectStep],
      ctx: MonorepoContext,
      project: ProjectReleaseInfo
  ): IO[MonorepoContext] =
    validatePublishHooks(steps, ctx, project).flatMap(executePublishHooks(steps, _, project))

  private def validatePublishHooks(
      steps: Seq[ProjectStep],
      ctx: MonorepoContext,
      project: ProjectReleaseInfo
  ): IO[MonorepoContext] =
    steps.foldLeft(IO.pure(ctx)) { (ioCtx, step) =>
      ioCtx.flatMap(currentCtx => step.validate(currentCtx, project))
    }

  private def executePublishHooks(
      steps: Seq[ProjectStep],
      ctx: MonorepoContext,
      project: ProjectReleaseInfo
  ): IO[MonorepoContext] =
    steps.foldLeft(IO.pure(ctx)) { (ioCtx, step) =>
      ioCtx.flatMap(currentCtx => step.execute(currentCtx, project))
    }
}
