package io.release.monorepo

import cats.effect.IO
import cats.effect.Ref
import io.release.ReleaseManifestMetadata
import io.release.ReleaseSharedKeys
import io.release.TestSupport
import io.release.monorepo.internal.*
import io.release.monorepo.internal.MonorepoStepAliases.AnyStep
import io.release.monorepo.internal.MonorepoStepAliases.ProjectStep
import io.release.monorepo.internal.steps.MonorepoPublishSteps
import io.release.monorepo.internal.steps.MonorepoPublishWorkflow
import io.release.monorepo.internal.steps.MonorepoReleaseSteps
import io.release.monorepo.internal.steps.MonorepoStepTestCompat
import io.release.runtime.engine.BuiltInStepRole
import io.release.runtime.engine.ProcessStep
import io.release.runtime.sbt.SbtRuntime
import io.release.vcs.Vcs
import munit.CatsEffectSuite
import sbt.Keys.*
import sbt.Resolver
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

        // `tag-preflight` is present: the installed `beforeTag` hook does not opt
        // in to `mayChangeTagSettings`, so the early preflight stays active.
        // The opt-out path is exercised below in the `mayChangeTagSettings` tests.
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
            "tag-preflight",
            "set-release-version",
            "commit-release-versions",
            "before-tag:before-tag",
            "plan-tag-names",
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

  test(
    "compile - auto-disable tag-preflight when an intervening hook flags mayChangeTagSettings"
  ) {
    val projectPhases = Seq(
      "beforeReleaseVersionWrite" -> ((s: MonorepoReleasePlugin.autoImport.type) =>
        s.releaseIOMonorepoHooksBeforeReleaseVersionWrite
      ),
      "afterReleaseVersionWrite"  -> ((s: MonorepoReleasePlugin.autoImport.type) =>
        s.releaseIOMonorepoHooksAfterReleaseVersionWrite
      ),
      "beforeTag"                 -> ((s: MonorepoReleasePlugin.autoImport.type) =>
        s.releaseIOMonorepoHooksBeforeTag
      )
    )
    val globalPhases  = Seq(
      "beforeReleaseCommit" -> ((s: MonorepoReleasePlugin.autoImport.type) =>
        s.releaseIOMonorepoHooksBeforeReleaseCommit
      ),
      "afterReleaseCommit"  -> ((s: MonorepoReleasePlugin.autoImport.type) =>
        s.releaseIOMonorepoHooksAfterReleaseCommit
      )
    )

    val projectChecks = projectPhases.foldLeft(IO.unit) { case (acc, (label, key)) =>
      acc *> {
        val hookSetting = key(MonorepoReleasePlugin.autoImport) := Seq(
          MonorepoProjectHookIO
            .sideEffect(s"$label-hook")((_, _) => IO.unit)
            .copy(mayChangeTagSettings = true)
        )
        hookFixtureResource(s"monorepo-tag-preflight-disabled-$label", Seq(hookSetting)).use {
          fixture =>
            compileLifecycle(fixture.state).map { steps =>
              assert(
                !steps.map(_.name).contains("tag-preflight"),
                s"tag-preflight should be auto-disabled when a $label per-project hook " +
                  s"with mayChangeTagSettings = true is configured, but found it in: " +
                  steps.map(_.name).mkString(", ")
              )
            }
        }
      }
    }

    val globalChecks = globalPhases.foldLeft(IO.unit) { case (acc, (label, key)) =>
      acc *> {
        val hookSetting = key(MonorepoReleasePlugin.autoImport) := Seq(
          MonorepoGlobalHookIO
            .sideEffect(s"$label-hook")(_ => IO.unit)
            .copy(mayChangeTagSettings = true)
        )
        hookFixtureResource(s"monorepo-tag-preflight-disabled-$label", Seq(hookSetting)).use {
          fixture =>
            compileLifecycle(fixture.state).map { steps =>
              assert(
                !steps.map(_.name).contains("tag-preflight"),
                s"tag-preflight should be auto-disabled when a $label global hook " +
                  s"with mayChangeTagSettings = true is configured, but found it in: " +
                  steps.map(_.name).mkString(", ")
              )
            }
        }
      }
    }

    projectChecks *> globalChecks
  }

  test(
    "compile - keep tag-preflight enabled when intervening hooks do not flag mayChangeTagSettings"
  ) {
    val projectPhases = Seq(
      "beforeReleaseVersionWrite" -> ((s: MonorepoReleasePlugin.autoImport.type) =>
        s.releaseIOMonorepoHooksBeforeReleaseVersionWrite
      ),
      "afterReleaseVersionWrite"  -> ((s: MonorepoReleasePlugin.autoImport.type) =>
        s.releaseIOMonorepoHooksAfterReleaseVersionWrite
      ),
      "beforeTag"                 -> ((s: MonorepoReleasePlugin.autoImport.type) =>
        s.releaseIOMonorepoHooksBeforeTag
      )
    )
    val globalPhases  = Seq(
      "beforeReleaseCommit" -> ((s: MonorepoReleasePlugin.autoImport.type) =>
        s.releaseIOMonorepoHooksBeforeReleaseCommit
      ),
      "afterReleaseCommit"  -> ((s: MonorepoReleasePlugin.autoImport.type) =>
        s.releaseIOMonorepoHooksAfterReleaseCommit
      )
    )

    val projectChecks = projectPhases.foldLeft(IO.unit) { case (acc, (label, key)) =>
      acc *> {
        val hookSetting = key(MonorepoReleasePlugin.autoImport) := Seq(
          MonorepoProjectHookIO.sideEffect(s"$label-hook")((_, _) => IO.unit)
        )
        hookFixtureResource(s"monorepo-tag-preflight-enabled-$label", Seq(hookSetting)).use {
          fixture =>
            compileLifecycle(fixture.state).map { steps =>
              assert(
                steps.map(_.name).contains("tag-preflight"),
                s"tag-preflight should remain enabled when a $label per-project hook without " +
                  s"mayChangeTagSettings is configured, but it was missing from: " +
                  steps.map(_.name).mkString(", ")
              )
            }
        }
      }
    }

    val globalChecks = globalPhases.foldLeft(IO.unit) { case (acc, (label, key)) =>
      acc *> {
        val hookSetting = key(MonorepoReleasePlugin.autoImport) := Seq(
          MonorepoGlobalHookIO.sideEffect(s"$label-hook")(_ => IO.unit)
        )
        hookFixtureResource(s"monorepo-tag-preflight-enabled-$label", Seq(hookSetting)).use {
          fixture =>
            compileLifecycle(fixture.state).map { steps =>
              assert(
                steps.map(_.name).contains("tag-preflight"),
                s"tag-preflight should remain enabled when a $label global hook without " +
                  s"mayChangeTagSettings is configured, but it was missing from: " +
                  steps.map(_.name).mkString(", ")
              )
            }
        }
      }
    }

    projectChecks *> globalChecks
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
        val publishedKey        = MonorepoPublishWorkflow.publishGateKey(baseEnabledCtx, project)
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
          val publishedKey        = MonorepoPublishWorkflow.publishGateKey(baseValidateCtx, project)
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
          published212Key = MonorepoPublishWorkflow.publishGateKey(validate212Ctx, mutatedProject)
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

  test("compile - freeze publish hook gates under the overlay-effective Scala version") {
    Ref.of[IO, List[String]](Nil).flatMap { observed =>
      val rootSettings = publishHookSettings(observed) ++ Seq(
        MonorepoReleasePlugin.autoImport.releaseIOMonorepoPublishChecks := true
      )
      val coreSettings = Seq(
        version                                  := "0.1.0-SNAPSHOT",
        scalaVersion                             := {
          if (version.value == "1.0.0") TestSupport.alternateScalaVersion
          else TestSupport.CurrentScalaVersion
        },
        publishTo                                := Some(Resolver.file("local", new File("."))),
        ReleaseSharedKeys.releaseIOPublishAction := { /* no-op publish */ }
      )

      hookFixtureResource(
        "monorepo-hook-compiler-overlay-scala-key",
        rootSettings,
        coreSettings
      ).use { fixture =>
        val ctx     = fixture.context(
          selectedProjectIds = Seq("core"),
          versionsById = Map("core" -> ("1.0.0" -> "1.1.0-SNAPSHOT"))
        )
        val project = ctx.currentProjects.head

        compileLifecycle(fixture.state).flatMap { steps =>
          val publishHooks = publishProjectHooksOnly(steps)
          val before       = publishHooks
            .find(_.name.startsWith("before-publish:"))
            .getOrElse(fail("Expected before-publish hook"))
          val after        = publishHooks
            .find(_.name.startsWith("after-publish:"))
            .getOrElse(fail("Expected after-publish hook"))

          for {
            beforeValidated  <- before.validate(ctx, project)
            publishValidated <- MonorepoPublishSteps.publishArtifacts.validate(
                                  beforeValidated,
                                  project
                                )
            afterValidated   <- after.validate(publishValidated, project)
            executeState      = TestSupport.appendSessionSettings(
                                  afterValidated.state,
                                  Seq(project.ref / version := "1.0.0")
                                )
            executeCtx        = afterValidated.withState(executeState)
            afterBefore      <- before.execute(executeCtx, project)
            afterPublish     <- MonorepoPublishSteps.publishArtifacts.execute(
                                  afterBefore,
                                  project
                                )
            _                <- after.execute(afterPublish, project)
            events           <- observed.get
          } yield assertEquals(
            events,
            List("validate-before", "validate-after", "execute-before", "execute-after")
          )
        }
      }
    }
  }

  test("compile - run after-publish under the source iteration when publish changes Scala") {
    Ref.of[IO, List[String]](Nil).flatMap { observed =>
      val rootSettings = publishHookSettings(observed) ++ Seq(
        MonorepoReleasePlugin.autoImport.releaseIOMonorepoPublishChecks := true
      )
      val coreSettings = Seq(
        scalaVersion   := TestSupport.CurrentScalaVersion,
        publish / skip := false,
        publishTo      := Some(Resolver.file("local", new File(".")))
      )

      hookFixtureResource(
        "monorepo-hook-compiler-publish-action-scala-drift",
        rootSettings,
        coreSettings
      ).use { fixture =>
        val baseCtx = fixture.context(selectedProjectIds = Seq("core"))
        val project = baseCtx.currentProjects.head
        val marker  = new File(fixture.dir, "published.txt")
        val ctx     = baseCtx.withState(
          TestSupport.appendSessionSettings(
            baseCtx.state,
            Seq(
              MonorepoStepTestCompat.publishActionWithScalaStateMutation(
                project.ref,
                TestSupport.alternateScalaVersion,
                marker
              )
            )
          )
        )

        compileLifecycle(fixture.state).flatMap { steps =>
          val publishHooks = publishProjectHooksOnly(steps)
          val before       = publishHooks
            .find(_.name.startsWith("before-publish:"))
            .getOrElse(fail("Expected before-publish hook"))
          val after        = publishHooks
            .find(_.name.startsWith("after-publish:"))
            .getOrElse(fail("Expected after-publish hook"))

          for {
            beforeValidated  <- before.validate(ctx, project)
            publishValidated <- MonorepoPublishSteps.publishArtifacts.validate(
                                  beforeValidated,
                                  project
                                )
            afterValidated   <- after.validate(publishValidated, project)
            afterBefore      <- before.execute(afterValidated, project)
            afterPublish     <- MonorepoPublishSteps.publishArtifacts.execute(
                                  afterBefore,
                                  project
                                )
            _                <- after.execute(afterPublish, project)
            events           <- observed.get
            published        <- IO.blocking(marker.exists())
            liveScala         = SbtRuntime
                                  .extracted(afterPublish.state)
                                  .get(project.ref / scalaVersion)
          } yield {
            assert(published)
            assertEquals(liveScala, TestSupport.alternateScalaVersion)
            assertEquals(
              events,
              List("validate-before", "validate-after", "execute-before", "execute-after")
            )
          }
        }
      }
    }
  }

  test("compile - share one authoritative skip probe across publish hooks and validation") {
    Ref.of[IO, List[String]](Nil).flatMap { observed =>
      val beforeHooks  = Seq(
        MonorepoProjectHookIO(
          name = "before-publish-one",
          execute = (ctx, _) => observed.update(_ :+ "execute-before-one").as(ctx),
          validate = (_, _) => observed.update(_ :+ "validate-before-one")
        ),
        MonorepoProjectHookIO(
          name = "before-publish-two",
          execute = (ctx, _) => observed.update(_ :+ "execute-before-two").as(ctx),
          validate = (_, _) => observed.update(_ :+ "validate-before-two")
        )
      )
      val afterHooks   = Seq(
        MonorepoProjectHookIO(
          name = "after-publish",
          execute = (ctx, _) => observed.update(_ :+ "execute-after").as(ctx),
          validate = (_, _) => observed.update(_ :+ "validate-after")
        )
      )
      val rootSettings = Seq(
        MonorepoReleasePlugin.autoImport.releaseIOMonorepoPublishChecks      := true,
        MonorepoReleasePlugin.autoImport.releaseIOMonorepoHooksBeforePublish := beforeHooks,
        MonorepoReleasePlugin.autoImport.releaseIOMonorepoHooksAfterPublish  := afterHooks
      )

      dynamicHookFixtureResource(
        "monorepo-hook-compiler-shared-publish-probe",
        rootSettings
      ) { dir =>
        Seq(
          scalaVersion                             := TestSupport.CurrentScalaVersion,
          MonorepoStepTestCompat.firstPublishSkipEvaluationReturnsTrue(
            new File(dir, "publish-skip-evaluations.txt")
          ),
          publishTo                                := None,
          ReleaseSharedKeys.releaseIOPublishAction :=
            sbt.IO.touch(new File(dir, "published.txt"))
        )
      }.use { fixture =>
        val ctx       = fixture.context(selectedProjectIds = Seq("core"))
        val project   = ctx.currentProjects.head
        val probe     = new File(fixture.dir, "publish-skip-evaluations.txt")
        val published = new File(fixture.dir, "published.txt")

        compileLifecycle(fixture.state).flatMap { steps =>
          val flow = publishProjectFlowOnly(steps)

          for {
            validated   <- validatePublishHooks(flow, ctx, project)
            result      <- executePublishHooks(flow, validated, project)
            evaluations <- IO.blocking(sbt.IO.read(probe))
            didPublish  <- IO.blocking(published.exists())
            events      <- observed.get
          } yield {
            assertEquals(evaluations, "1")
            assert(!didPublish)
            assertEquals(events, Nil)
            assertEquals(
              validated.validatedPublishEligibility(
                MonorepoContext.PublishIteration(
                  project.ref,
                  TestSupport.CurrentScalaVersion
                )
              ),
              Some(false)
            )
            assertEquals(result.publishExecutedKeys, Some(Set.empty[String]))
          }
        }
      }
    }
  }

  test("compose - cross publish retains persistent cross versions without expanding hooks") {
    Ref.of[IO, List[String]](Nil).flatMap { afterPublishScalaVersions =>
      val scalaA       = TestSupport.CurrentScalaVersion
      val scalaB       = TestSupport.alternateScalaVersion
      val scalaC       = "2.13.16"
      val crossAB      = Seq(scalaA, scalaB)
      val crossABC     = Seq(scalaA, scalaB, scalaC)
      val rootSettings = Seq(
        MonorepoReleasePlugin.autoImport.releaseIOMonorepoPublishChecks     := true,
        MonorepoReleasePlugin.autoImport.releaseIOMonorepoHooksAfterPublish := Seq(
          MonorepoProjectHookIO(
            name = "after-publish",
            execute = (ctx, project) =>
              IO.blocking(
                SbtRuntime.extracted(ctx.state).get(project.ref / scalaVersion)
              ).flatMap(version => afterPublishScalaVersions.update(_ :+ version))
                .as(ctx),
            validate = (_, _) => IO.unit
          )
        )
      )

      dynamicHookFixtureResource(
        "monorepo-cross-publish-persistent-cross-versions",
        rootSettings
      ) { dir =>
        Seq(
          scalaVersion       := scalaA,
          crossScalaVersions := crossAB,
          publish / skip     := false,
          publishTo          := Some(Resolver.file("local", new File(dir, "repository")))
        )
      }.use { fixture =>
        val baseCtx   = fixture.context(selectedProjectIds = Seq("core"))
        val project   = baseCtx.currentProjects.head
        val published = new File(fixture.dir, "published-scala-versions.txt")
        val ctx       = baseCtx.withState(
          TestSupport.appendSessionSettings(
            baseCtx.state,
            Seq(
              MonorepoStepTestCompat.publishActionWithPersistentCrossScalaVersionsMutation(
                project.ref,
                crossABC,
                published
              )
            )
          )
        )
        compileLifecycle(fixture.state).flatMap { steps =>
          val flow = canonicalPublishFlow(steps)

          MonorepoComposer.compose(flow, crossBuild = true)(ctx).flatMap { result =>
            for {
              publishedVersions <- IO.blocking(sbt.IO.readLines(published))
              afterVersions     <- afterPublishScalaVersions.get
              finalScala         = SbtRuntime
                                     .extracted(result.state)
                                     .get(project.ref / scalaVersion)
              finalCrossVersions = SbtRuntime
                                     .extracted(result.state)
                                     .get(project.ref / crossScalaVersions)
            } yield {
              assert(!result.failed)
              assertEquals(publishedVersions, crossAB)
              assertEquals(afterVersions, crossAB.toList)
              assertEquals(finalScala, scalaA)
              assertEquals(finalCrossVersions, crossABC)
            }
          }
        }
      }
    }
  }

  test("compose - checks-disabled success runs after-publish") {
    Ref.of[IO, List[String]](Nil).flatMap { observed =>
      val rootSettings = Seq(
        MonorepoReleasePlugin.autoImport.releaseIOMonorepoPublishChecks     := false,
        MonorepoReleasePlugin.autoImport.releaseIOMonorepoHooksAfterPublish := Seq(
          MonorepoProjectHookIO(
            name = "after-publish",
            execute = (ctx, _) => observed.update(_ :+ "execute-after").as(ctx),
            validate = (_, _) => observed.update(_ :+ "validate-after")
          )
        )
      )

      dynamicHookFixtureResource(
        "monorepo-probe-less-publish",
        rootSettings
      ) { dir =>
        Seq(
          scalaVersion                             := TestSupport.CurrentScalaVersion,
          publish / skip                           := false,
          publishTo                                := None,
          ReleaseSharedKeys.releaseIOPublishAction :=
            sbt.IO.touch(new File(dir, "published.txt"))
        )
      }.use { fixture =>
        val ctx       = fixture.context(selectedProjectIds = Seq("core"))
        val project   = ctx.currentProjects.head
        val input     = MonorepoContext.PublishIteration(
          project.ref,
          TestSupport.CurrentScalaVersion
        )
        val published = new File(fixture.dir, "published.txt")

        compileLifecycle(fixture.state).flatMap { steps =>
          MonorepoComposer
            .compose(canonicalPublishFlow(steps), crossBuild = false)(ctx)
            .flatMap { result =>
              for {
                events <- observed.get
                exists <- IO.blocking(published.exists())
              } yield {
                assert(exists)
                assertEquals(
                  result.publishValidationProbe(input).map(_.targetProgress),
                  Some(MonorepoContext.PublishTargetProgress.NotRequired)
                )
                assertEquals(result.validatedPublishGateDecision(input), Some(true))
                assert(!result.hasValidatedPublishEligibilitySnapshot)
                assertEquals(result.publishExecutedKeys, Some(Set(input.gateKey)))
                assertEquals(
                  result.afterPublishOutcome(input),
                  MonorepoContext.AfterPublishOutcome(input, succeeded = true)
                )
                assertEquals(events, List("validate-after", "execute-after"))
                assertEquals(events.count(_ == "validate-after"), 1)
                assertEquals(events.count(_ == "execute-after"), 1)
              }
            }
        }
      }
    }
  }

  test("compose - upfront after-publish maps a pre-overlay attempt to its entry") {
    Ref.of[IO, List[String]](Nil).flatMap { observed =>
      val rootSettings = Seq(
        MonorepoReleasePlugin.autoImport.releaseIOMonorepoPublishChecks     := false,
        MonorepoReleasePlugin.autoImport.releaseIOMonorepoHooksAfterPublish := Seq(
          MonorepoProjectHookIO(
            name = "after-publish",
            execute = (ctx, _) => observed.update(_ :+ "execute-after").as(ctx),
            validate = (_, _) => observed.update(_ :+ "validate-after")
          )
        )
      )

      dynamicHookFixtureResource(
        "monorepo-upfront-publish-overlay-entry-gate",
        rootSettings
      ) { dir =>
        Seq(
          version                                  := "0.1.0-SNAPSHOT",
          scalaVersion                             := {
            if (version.value == "1.0.0") TestSupport.alternateScalaVersion
            else TestSupport.CurrentScalaVersion
          },
          publish / skip                           := false,
          ReleaseSharedKeys.releaseIOPublishAction :=
            sbt.IO.touch(new File(dir, "published.txt"))
        )
      }.use { fixture =>
        val ctx       = fixture.context(
          selectedProjectIds = Seq("core"),
          versionsById = Map("core" -> ("1.0.0" -> "1.1.0-SNAPSHOT"))
        )
        val project   = ctx.currentProjects.head
        val input     = MonorepoContext.PublishIteration(
          project.ref,
          TestSupport.CurrentScalaVersion
        )
        val entry     = MonorepoContext.PublishIteration(
          project.ref,
          TestSupport.alternateScalaVersion
        )
        val published = new File(fixture.dir, "published.txt")

        compileLifecycle(fixture.state).flatMap { steps =>
          val flow = canonicalPublishFlow(steps)

          MonorepoComposer.compose(flow, crossBuild = false)(ctx).flatMap { result =>
            for {
              events     <- observed.get
              didPublish <- IO.blocking(published.exists())
              liveScala   = SbtRuntime.extracted(result.state).get(project.ref / scalaVersion)
            } yield {
              val probe = result
                .publishValidationProbe(input)
                .getOrElse(fail("Expected publish validation probe"))

              assert(!result.failed)
              assert(didPublish)
              assertEquals(liveScala, TestSupport.CurrentScalaVersion)
              assertEquals(probe.entry, entry)
              assertEquals(
                MonorepoPublishWorkflow.afterPublishGateKey(result, project),
                entry.gateKey
              )
              assertEquals(events, List("validate-after", "execute-after"))
            }
          }
        }
      }
    }
  }

  test("compile - key checks-disabled after-publish under the attempt-scoped iteration") {
    Ref.of[IO, List[String]](Nil).flatMap { observed =>
      val settings = publishHookSettings(observed) ++ Seq(
        MonorepoReleasePlugin.autoImport.releaseIOMonorepoPublishChecks := false
      )

      dynamicHookFixtureResource(
        "monorepo-hook-compiler-post-skip-after-publish-key",
        settings
      ) { dir =>
        Seq(
          scalaVersion                             := TestSupport.CurrentScalaVersion,
          publish / skip                           := false,
          ReleaseSharedKeys.releaseIOPublishAction :=
            sbt.IO.touch(new File(dir, "published.txt"))
        )
      }.use { fixture =>
        val baseCtx = fixture.context(selectedProjectIds = Seq("core"))
        val project = baseCtx.currentProjects.head
        val marker  = new File(fixture.dir, "published.txt")
        val ctx     = baseCtx.withState(
          TestSupport.appendSessionSettings(
            baseCtx.state,
            Seq(
              MonorepoStepTestCompat.publishSkipWithScalaStateMutation(
                project.ref,
                TestSupport.alternateScalaVersion,
                skipped = false
              )
            )
          )
        )

        compileLifecycle(fixture.state).flatMap { steps =>
          val flow = publishProjectFlowOnly(steps)

          for {
            validated  <- validatePublishHooks(flow, ctx, project)
            result     <- executePublishHooks(flow, validated, project)
            didPublish <- IO.blocking(marker.exists())
            events     <- observed.get
            liveScala   = SbtRuntime.extracted(result.state).get(project.ref / scalaVersion)
          } yield {
            assert(didPublish)
            assertEquals(liveScala, TestSupport.alternateScalaVersion)
            assertEquals(
              events,
              List("validate-before", "validate-after", "execute-before", "execute-after")
            )
          }
        }
      }
    }
  }

  test("compile - retain successful post-skip source when publish restores entry Scala") {
    Ref.of[IO, List[String]](Nil).flatMap { observed =>
      val settings = publishHookSettings(observed) ++ Seq(
        MonorepoReleasePlugin.autoImport.releaseIOMonorepoPublishChecks := false
      )

      dynamicHookFixtureResource(
        "monorepo-hook-compiler-restored-publish-source",
        settings
      ) { dir =>
        Seq(
          scalaVersion   := TestSupport.CurrentScalaVersion,
          publish / skip := false
        )
      }.use { fixture =>
        val baseCtx = fixture.context(selectedProjectIds = Seq("core"))
        val project = baseCtx.currentProjects.head
        val marker  = new File(fixture.dir, "published.txt")
        val ctx     = baseCtx.withState(
          TestSupport.appendSessionSettings(
            baseCtx.state,
            Seq(
              MonorepoStepTestCompat.publishSkipWithScalaStateMutation(
                project.ref,
                TestSupport.alternateScalaVersion,
                skipped = false
              ),
              MonorepoStepTestCompat.publishActionWithScalaStateMutation(
                project.ref,
                TestSupport.CurrentScalaVersion,
                marker
              )
            )
          )
        )

        compileLifecycle(fixture.state).flatMap { steps =>
          val flow = publishProjectFlowOnly(steps)

          for {
            validated  <- validatePublishHooks(flow, ctx, project)
            result     <- executePublishHooks(flow, validated, project)
            didPublish <- IO.blocking(marker.exists())
            events     <- observed.get
            liveScala   = SbtRuntime.extracted(result.state).get(project.ref / scalaVersion)
          } yield {
            assert(didPublish)
            assertEquals(liveScala, TestSupport.CurrentScalaVersion)
            assertEquals(
              events,
              List("validate-before", "validate-after", "execute-before", "execute-after")
            )
            assertEquals(events.count(_ == "execute-after"), 1)
          }
        }
      }
    }
  }

  test("compile - keep converged post-skip after-publish gates attempt scoped") {
    Ref.of[IO, List[String]](Nil).flatMap { observed =>
      val settings = publishHookSettings(observed) ++ Seq(
        MonorepoReleasePlugin.autoImport.releaseIOMonorepoPublishChecks := false
      )

      hookFixtureResource(
        "monorepo-hook-compiler-converged-after-publish-gates",
        settings,
        Seq(scalaVersion := TestSupport.CurrentScalaVersion)
      ).use { fixture =>
        val baseCtx = fixture.context(selectedProjectIds = Seq("core"))
        val project = baseCtx.currentProjects.head

        for {
          stateA       <- stateWithProjectScalaVersion(
                            baseCtx.state,
                            project.ref,
                            TestSupport.CurrentScalaVersion
                          )
          stateB       <- stateWithProjectScalaVersion(
                            baseCtx.state,
                            project.ref,
                            TestSupport.alternateScalaVersion
                          )
          steps        <- compileLifecycle(fixture.state)
          after         = publishProjectHooksOnly(steps)
                            .find(_.name.startsWith("after-publish:"))
                            .getOrElse(fail("Expected after-publish hook"))
          attemptA      = MonorepoContext.PublishIteration(
                            project.ref,
                            TestSupport.CurrentScalaVersion
                          )
          attemptB      = MonorepoContext.PublishIteration(
                            project.ref,
                            TestSupport.alternateScalaVersion
                          )
          probeA        = MonorepoContext.PublishValidationProbe(
                            input = attemptA,
                            entry = attemptA,
                            postSkip = attemptB,
                            publishSkipped = false,
                            targetProgress = MonorepoContext.PublishTargetProgress.NotRequired
                          )
          probeB        = MonorepoContext.PublishValidationProbe(
                            input = attemptB,
                            entry = attemptB,
                            postSkip = attemptB,
                            publishSkipped = true,
                            targetProgress = MonorepoContext.PublishTargetProgress.NotRequired
                          )
          validationCtx = baseCtx
                            .withState(stateA)
                            .recordPublishValidationProbe(probeA)
                            .recordPublishValidationProbe(probeB)
          validatedA   <- after.validate(validationCtx, project)
          validatedB   <- after.validate(validatedA.withState(stateB), project)
          executionCtx  = validatedB.beginPublishExecutionBatch
                            .recordPublishAttempt(attemptA)
                            .recordPublishSucceeded(
                              attemptIteration = attemptA,
                              actionIteration = attemptB,
                              hookSource = attemptB,
                              taskReturnedIteration = attemptB
                            )
                            .recordPublishAttempt(attemptB)
          executedA    <- after.execute(executionCtx.withState(stateA), project)
          _            <- after.execute(executedA.withState(stateB), project)
          events       <- observed.get
        } yield {
          assertEquals(
            executionCtx.afterPublishOutcome(attemptA),
            MonorepoContext.AfterPublishOutcome(attemptA, succeeded = true)
          )
          assertEquals(
            executionCtx.afterPublishOutcome(attemptB),
            MonorepoContext.AfterPublishOutcome(attemptB, succeeded = false)
          )
          assertEquals(events, List("validate-after", "execute-after"))
        }
      }
    }
  }

  test("compile - attribute metadata-shifted publish success to the validated hook source") {
    Ref.of[IO, List[String]](Nil).flatMap { observed =>
      val settings = publishHookSettings(observed) ++ Seq(
        MonorepoReleasePlugin.autoImport.releaseIOMonorepoPublishChecks := false
      )

      dynamicHookFixtureResource(
        "monorepo-hook-compiler-metadata-shifted-publish-source",
        settings
      ) { dir =>
        Seq(
          ReleaseManifestMetadata.releaseIOInternalReleaseHash := None,
          scalaVersion                                         :=
            ReleaseManifestMetadata.releaseIOInternalReleaseHash.value.fold(
              TestSupport.CurrentScalaVersion
            )(_ => TestSupport.alternateScalaVersion),
          publish / skip                                       := false,
          ReleaseSharedKeys.releaseIOPublishAction             :=
            sbt.IO.touch(new File(dir, "published.txt"))
        )
      }.use { fixture =>
        val baseCtx   = fixture.context(
          selectedProjectIds = Seq("core"),
          versionsById = Map("core" -> ("1.0.0" -> "1.1.0-SNAPSHOT")),
          vcs = Some(new PublishPreparationTestVcs(fixture.dir))
        )
        val project   = baseCtx.currentProjects.head
        val marker    = new File(fixture.dir, "published.txt")
        val hookKey   = MonorepoContext
          .PublishIteration(project.ref, TestSupport.CurrentScalaVersion)
          .gateKey
        val actionKey = MonorepoContext
          .PublishIteration(project.ref, TestSupport.alternateScalaVersion)
          .gateKey

        compileLifecycle(fixture.state).flatMap { steps =>
          val flow = publishProjectFlowOnly(steps)

          for {
            validated  <- validatePublishHooks(flow, baseCtx, project)
            result     <- executePublishHooks(flow, validated, project)
            didPublish <- IO.blocking(marker.exists())
            events     <- observed.get
            liveScala   = SbtRuntime.extracted(result.state).get(project.ref / scalaVersion)
          } yield {
            assert(didPublish)
            assertEquals(liveScala, TestSupport.alternateScalaVersion)
            assertEquals(MonorepoPublishWorkflow.afterPublishGateKey(result, project), hookKey)
            assert(MonorepoPublishWorkflow.didPublishForAfterHook(result, project))
            assert(result.publishExecutedKeys.exists(_.contains(actionKey)))
            assert(!result.publishExecutedKeys.exists(_.contains(hookKey)))
            assertEquals(
              events,
              List("validate-before", "validate-after", "execute-before", "execute-after")
            )
          }
        }
      }
    }
  }

  private def hookFixtureResource(
      prefix: String,
      rootSettings: Seq[Setting[?]] = Nil,
      projectSettings: Seq[Setting[?]] = Nil
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
          settings = Seq(publish / skip := false) ++ projectSettings
        )
      )
    }

  private def dynamicHookFixtureResource(
      prefix: String,
      rootSettings: Seq[Setting[?]]
  )(
      projectSettings: File => Seq[Setting[?]]
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
          settings = projectSettings(dir)
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

  private def publishProjectFlowOnly(steps: Seq[AnyStep]): Seq[ProjectStep] =
    steps
      .flatMap(asProjectStep)
      .filter(step =>
        step.hasRole(BuiltInStepRole.PublishArtifacts) ||
          step.name.startsWith("before-publish:") ||
          step.name.startsWith("after-publish:")
      )

  private def canonicalPublishFlow(steps: Seq[AnyStep]): Seq[AnyStep] = {
    val boundary: AnyStep = ProcessStep.Single(
      name = MonorepoComposer.SelectionBoundary,
      execute = (ctx: MonorepoContext) => IO.pure(ctx),
      roles = Set(BuiltInStepRole.SelectionBoundary)
    )

    boundary +: publishProjectFlowOnly(steps)
  }

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

private[monorepo] final class PublishPreparationTestVcs(
    override val baseDir: File
) extends Vcs {
  override def commandName: String                                                         = "test"
  override def currentHash: IO[String]                                                     = IO.pure("publish-test-hash")
  override def currentBranch: IO[String]                                                   = IO.pure("main")
  override def trackingRemote: IO[String]                                                  = IO.pure("origin")
  override def upstreamTrackingHash: IO[Option[String]]                                    = IO.pure(None)
  override def hasUpstream: IO[Boolean]                                                    = IO.pure(false)
  override def isBehindRemote: IO[Boolean]                                                 = IO.pure(false)
  override def existsTag(name: String): IO[Boolean]                                        = IO.pure(false)
  override def modifiedFiles: IO[Seq[String]]                                              = IO.pure(Seq.empty)
  override def stagedFiles: IO[Seq[String]]                                                = IO.pure(Seq.empty)
  override def untrackedFiles: IO[Seq[String]]                                             = IO.pure(Seq.empty)
  override def status: IO[String]                                                          = IO.pure("")
  override def checkRemote(remote: String): IO[Int]                                        = IO.pure(0)
  override def add(files: String*): IO[Unit]                                               = IO.unit
  override def commit(message: String, sign: Boolean, signOff: Boolean): IO[Unit]          = IO.unit
  override def tag(name: String, comment: String, sign: Boolean, force: Boolean): IO[Unit] =
    IO.unit
  override def pushChanges: IO[Unit]                                                       = IO.unit
}
