package io.release.runtime.engine

import cats.effect.IO
import cats.effect.Ref
import io.release.runtime.TrackedContextHandle
import munit.CatsEffectSuite

import LifecycleCompilerSpec.{ItemHook, SingleHook, TestConfig, TestContext}

class LifecycleCompilerSpec extends CatsEffectSuite {

  private def executeSingle(
      step: ProcessStep.Single[TestContext],
      ctx: TestContext,
      tracked: Boolean
  ): IO[TestContext] =
    if (tracked)
      TrackedContextHandle.create(ctx).flatMap { handle =>
        step.executeTracked(handle).flatMap(_ => handle.get)
      }
    else step.execute(ctx)

  private def executePerItem(
      step: ProcessStep.PerItem[TestContext, String],
      ctx: TestContext,
      item: String,
      tracked: Boolean
  ): IO[TestContext] =
    if (tracked)
      TrackedContextHandle.create(ctx).flatMap { handle =>
        step.executeTracked(handle, item).flatMap(_ => handle.get)
      }
    else step.execute(ctx, item)

  private def singleHookPhase[I](
      phase: String,
      resolveHooks: TestConfig => Seq[SingleHook],
      gate: TestContext => IO[Boolean],
      crossBuild: Boolean = false
  ): LifecycleCompiler.Phase[TestConfig, TestContext, I] =
    LifecycleCompiler.singleHookPhase(
      phase = phase,
      resolveHooks = resolveHooks,
      gate = gate,
      nameOf = (hook: SingleHook) => hook.name,
      executeOf = (hook: SingleHook) => hook.execute,
      validateOf = (hook: SingleHook) => hook.validate,
      crossBuild = crossBuild
    )

  private def itemHookPhase(
      phase: String,
      resolveHooks: TestConfig => Seq[ItemHook],
      gate: (TestContext, String) => IO[Boolean],
      crossBuild: Boolean = false
  ): LifecycleCompiler.Phase[TestConfig, TestContext, String] =
    LifecycleCompiler.perItemHookPhase(
      phase = phase,
      resolveHooks = resolveHooks,
      gate = gate,
      nameOf = (hook: ItemHook) => hook.name,
      executeOf = (hook: ItemHook) => hook.execute,
      validateOf = (hook: ItemHook) => hook.validate,
      crossBuild = crossBuild
    )

  test("fold - dispatch Single and PerItem branches") {
    val single  = ProcessStep.Single[TestContext](
      name = "a",
      execute = ctx => IO.pure(ctx)
    )
    val perItem = ProcessStep.PerItem[TestContext, String](
      name = "b",
      execute = (ctx, _) => IO.pure(ctx)
    )
    assertEquals(
      ProcessStep.fold[TestContext, Nothing, String](single)(
        (s: ProcessStep.Single[TestContext]) => s.name,
        (_: ProcessStep.PerItem[TestContext, Nothing]) => "wrong"
      ),
      "a"
    )
    assertEquals(
      ProcessStep.fold[TestContext, String, String](perItem)(
        (_: ProcessStep.Single[TestContext]) => "wrong",
        (p: ProcessStep.PerItem[TestContext, String]) => p.name
      ),
      "b"
    )
  }

  test("defaults - return built-in steps only in canonical order") {
    val singleStep = ProcessStep.Single[TestContext](
      name = "initialize",
      execute = ctx => IO.pure(ctx)
    )
    val itemStep   = ProcessStep.PerItem[TestContext, String](
      name = "publish",
      execute = (ctx, _) => IO.pure(ctx)
    )
    val phases     = Seq[LifecycleCompiler.Phase[TestConfig, TestContext, String]](
      LifecycleCompiler.builtIn(singleStep),
      singleHookPhase(
        phase = "before-publish",
        resolveHooks = _.singleHooks,
        gate = _ => IO.pure(true)
      ),
      LifecycleCompiler.builtIn(itemStep),
      itemHookPhase(
        phase = "after-publish",
        resolveHooks = _.itemHooks,
        gate = (_, _) => IO.pure(true)
      )
    )

    assertEquals(LifecycleCompiler.defaults(phases).map(_.name), Seq("initialize", "publish"))
  }

  test("compileSingle - compile named single-context hook steps in order") {
    val phases = Seq[LifecycleCompiler.Phase[TestConfig, TestContext, Nothing]](
      singleHookPhase(
        phase = "before-version",
        resolveHooks = _.singleHooks,
        gate = _ => IO.pure(true),
        crossBuild = true
      )
    )

    LifecycleCompiler
      .compileSingle(
        TestConfig(
          singleHooks = Seq(
            SingleHook("resolve"),
            SingleHook("confirm")
          )
        ),
        phases
      )
      .map { compiled =>
        assertEquals(
          compiled.map(_.name),
          Seq("before-version:resolve", "before-version:confirm")
        )
        assert(compiled.forall(_.enableCrossBuild))
      }
  }

  test("compile - compile named per-item hook steps in order") {
    val phases = Seq[LifecycleCompiler.Phase[TestConfig, TestContext, String]](
      itemHookPhase(
        phase = "before-publish",
        resolveHooks = _.itemHooks,
        gate = (_, _) => IO.pure(true),
        crossBuild = true
      )
    )

    LifecycleCompiler
      .compile(
        TestConfig(
          itemHooks = Seq(
            ItemHook("prepare"),
            ItemHook("verify")
          )
        ),
        phases
      )
      .map { steps =>
        val compiled = steps.map { step =>
          ProcessStep.fold[TestContext, String, ProcessStep.PerItem[TestContext, String]](step)(
            _ => fail("expected PerItem hook step"),
            identity
          )
        }
        assertEquals(compiled.map(_.name), Seq("before-publish:prepare", "before-publish:verify"))
        assert(compiled.forall(_.enableCrossBuild))
      }
  }

  test("compile - frozen single gate reuses validation decision during execute") {
    Ref.of[IO, List[String]](Nil).flatMap { events =>
      Ref.of[IO, Int](0).flatMap { gateCalls =>
        val hook   = SingleHook(
          name = "publish-check",
          execute = ctx => events.update(_ :+ "execute").as(ctx),
          validate = _ => events.update(_ :+ "validate")
        )
        val phases =
          Seq[LifecycleCompiler.Phase[TestConfig, TestContext, Nothing]](
            LifecycleCompiler.singleHookPhase(
              phase = "before-publish",
              resolveHooks = _.singleHooks,
              gate = ctx => gateCalls.update(_ + 1).as(ctx.gateOpen),
              nameOf = (h: SingleHook) => h.name,
              executeOf = (h: SingleHook) => h.execute,
              validateOf = (h: SingleHook) => h.validate,
              freezeGateKey = Some(_ => "publish")
            )
          )
        for {
          steps     <- LifecycleCompiler
                         .compileSingle(TestConfig(singleHooks = Seq(hook)), phases)
          step       = steps.head
          validated <- step.validate(TestContext(gateOpen = true))
          _         <- step.execute(validated.copy(gateOpen = false))
          recorded  <- events.get
          calls     <- gateCalls.get
        } yield {
          assertEquals(
            recorded,
            List("validate", "execute")
          )
          assertEquals(calls, 1)
        }
      }
    }
  }

  test("compile - frozen single gate caches independent decisions per key") {
    Ref.of[IO, List[String]](Nil).flatMap { events =>
      Ref.of[IO, Int](0).flatMap { gateCalls =>
        val hook   = SingleHook(
          name = "publish-check",
          execute = ctx => events.update(_ :+ s"execute:${ctx.gateKey}").as(ctx),
          validate = ctx => events.update(_ :+ s"validate:${ctx.gateKey}")
        )
        val phases =
          Seq[LifecycleCompiler.Phase[TestConfig, TestContext, Nothing]](
            LifecycleCompiler.singleHookPhase(
              phase = "before-publish",
              resolveHooks = _.singleHooks,
              gate = ctx =>
                events.update(_ :+ s"gate:${ctx.gateKey}:${ctx.gateOpen}") *>
                  gateCalls.update(_ + 1).as(ctx.gateOpen),
              nameOf = (h: SingleHook) => h.name,
              executeOf = (h: SingleHook) => h.execute,
              validateOf = (h: SingleHook) => h.validate,
              freezeGateKey = Some(_.gateKey)
            )
          )
        val first  = TestContext(gateOpen = true, gateKey = "2.12")
        val second = TestContext(gateOpen = false, gateKey = "3")

        for {
          steps           <- LifecycleCompiler
                               .compileSingle(TestConfig(singleHooks = Seq(hook)), phases)
          step             = steps.head
          validatedFirst  <- step.validate(first)
          validatedSecond <- step.validate(second)
          _               <- step.execute(validatedFirst.copy(gateOpen = false))
          _               <- step.execute(validatedSecond.copy(gateOpen = true))
          recorded        <- events.get
          calls           <- gateCalls.get
        } yield {
          assertEquals(
            recorded,
            List(
              "gate:2.12:true",
              "validate:2.12",
              "gate:3:false",
              "execute:2.12"
            )
          )
          assertEquals(calls, 2)
        }
      }
    }
  }

  test("compile - frozen single gate execute fails fast when validate did not run") {
    val hook   = SingleHook(name = "publish-check")
    val phases =
      Seq[LifecycleCompiler.Phase[TestConfig, TestContext, Nothing]](
        LifecycleCompiler.singleHookPhase(
          phase = "before-publish",
          resolveHooks = _.singleHooks,
          gate = _ => IO.pure(true),
          nameOf = (h: SingleHook) => h.name,
          executeOf = (h: SingleHook) => h.execute,
          validateOf = (h: SingleHook) => h.validate,
          freezeGateKey = Some(_ => "core")
        )
      )

    LifecycleCompiler
      .compileSingle(TestConfig(singleHooks = Seq(hook)), phases)
      .flatMap { steps =>
        interceptMessageIO[IllegalStateException](
          "Frozen gate decision missing for key 'core'; validate must run before execute when freezeGateKey is set"
        ) {
          steps.head.execute(TestContext(gateOpen = true)).void
        }
      }
  }

  test("compile - frozen single validation resolves context, key, and decision together") {
    Ref.of[IO, List[String]](Nil).flatMap { events =>
      val hook   = SingleHook(
        name = "publish-check",
        execute = ctx => events.update(_ :+ "unexpected-execute").as(ctx),
        validate = _ => events.update(_ :+ "unexpected-validate")
      )
      val phases = Seq[LifecycleCompiler.Phase[TestConfig, TestContext, Nothing]](
        LifecycleCompiler.singleHookPhase(
          phase = "before-publish",
          resolveHooks = _.singleHooks,
          gate = _ => events.update(_ :+ "unexpected-gate").as(true),
          nameOf = (h: SingleHook) => h.name,
          executeOf = (h: SingleHook) => h.execute,
          validateOf = (h: SingleHook) => h.validate,
          freezeGateKey = Some(_.gateKey),
          freezeGateValidation = Some(ctx =>
            events
              .update(_ :+ "validate-key-and-decision")
              .as(
                LifecycleCompiler.FrozenGateValidation(
                  ctx.copy(gateKey = "execute"),
                  "execute",
                  decision = false
                )
              )
          )
        )
      )

      LifecycleCompiler
        .compileSingle(TestConfig(singleHooks = Seq(hook)), phases)
        .flatMap { steps =>
          val step = steps.head
          for {
            validated <- step.validate(TestContext(gateOpen = true, gateKey = "validation"))
            direct    <- executeSingle(step, validated, tracked = false)
            tracked   <- executeSingle(step, validated, tracked = true)
            recorded  <- events.get
          } yield {
            assertEquals(validated.gateKey, "execute")
            assertEquals(direct, validated)
            assertEquals(tracked, validated)
            assertEquals(recorded, List("validate-key-and-decision"))
          }
        }
    }
  }

  test("compile - frozen single gate narrows a missing key without caching it") {
    Ref.of[IO, Boolean](false).flatMap { allow =>
      val hook   = SingleHook(name = "publish-check")
      val phases = Seq[LifecycleCompiler.Phase[TestConfig, TestContext, Nothing]](
        LifecycleCompiler.singleHookPhase(
          phase = "before-publish",
          resolveHooks = _.singleHooks,
          gate = _ => IO.pure(true),
          nameOf = (h: SingleHook) => h.name,
          executeOf = (h: SingleHook) => h.execute,
          validateOf = (h: SingleHook) => h.validate,
          freezeGateKey = Some(_.gateKey),
          narrowExecute = Some(_ => allow.get),
          narrowOnMissingFrozenGate = true
        )
      )

      LifecycleCompiler
        .compileSingle(TestConfig(singleHooks = Seq(hook)), phases)
        .flatMap { steps =>
          val step     = steps.head
          val expected =
            "Frozen gate decision missing for key 'introduced'; validate must run before execute when freezeGateKey is set"
          for {
            validated <- step.validate(TestContext(gateOpen = true, gateKey = "validated"))
            skipped   <- executeSingle(
                           step,
                           validated.copy(gateKey = "introduced"),
                           tracked = false
                         )
            _         <- allow.set(true)
            failed    <- executeSingle(
                           step,
                           validated.copy(gateKey = "introduced"),
                           tracked = true
                         ).attempt
          } yield {
            assertEquals(skipped.gateKey, "introduced")
            assertEquals(failed.left.map(_.getMessage), Left(expected))
          }
        }
    }
  }

  test("compile - frozen per-item gate reuses validation decision during execute") {
    Ref.of[IO, List[String]](Nil).flatMap { events =>
      Ref.of[IO, Int](0).flatMap { gateCalls =>
        val hook   = ItemHook(
          name = "publish-check",
          execute = (ctx, _) => events.update(_ :+ "execute").as(ctx),
          validate = (_, _) => events.update(_ :+ "validate")
        )
        val phases = Seq[LifecycleCompiler.Phase[
          TestConfig,
          TestContext,
          String
        ]](
          LifecycleCompiler.perItemHookPhase(
            phase = "before-publish",
            resolveHooks = _.itemHooks,
            gate = (ctx, _) => gateCalls.update(_ + 1).as(ctx.gateOpen),
            nameOf = (h: ItemHook) => h.name,
            executeOf = (h: ItemHook) => h.execute,
            validateOf = (h: ItemHook) => h.validate,
            freezeGateKey = Some((_, item) => item)
          )
        )
        for {
          steps     <- LifecycleCompiler
                         .compile(TestConfig(itemHooks = Seq(hook)), phases)
          step       = ProcessStep
                         .fold[TestContext, String, ProcessStep.PerItem[TestContext, String]](
                           steps.head
                         )(
                           _ => fail("expected PerItem step"),
                           identity
                         )
          validated <- step.validate(
                         TestContext(gateOpen = true),
                         "core"
                       )
          _         <- step.execute(
                         validated.copy(gateOpen = false),
                         "core"
                       )
          recorded  <- events.get
          calls     <- gateCalls.get
        } yield {
          assertEquals(
            recorded,
            List("validate", "execute")
          )
          assertEquals(calls, 1)
        }
      }
    }
  }

  test("compile - frozen per-item gate execute fails fast when validate did not run") {
    val hook   = ItemHook(name = "publish-check")
    val phases = Seq[LifecycleCompiler.Phase[
      TestConfig,
      TestContext,
      String
    ]](
      LifecycleCompiler.perItemHookPhase(
        phase = "before-publish",
        resolveHooks = _.itemHooks,
        gate = (_, _) => IO.pure(true),
        nameOf = (h: ItemHook) => h.name,
        executeOf = (h: ItemHook) => h.execute,
        validateOf = (h: ItemHook) => h.validate,
        freezeGateKey = Some((_, item) => item)
      )
    )

    LifecycleCompiler
      .compile(TestConfig(itemHooks = Seq(hook)), phases)
      .flatMap { steps =>
        val step = ProcessStep
          .fold[TestContext, String, ProcessStep.PerItem[TestContext, String]](steps.head)(
            _ => fail("expected PerItem step"),
            identity
          )

        interceptMessageIO[IllegalStateException](
          "Frozen gate decision missing for key 'core'; validate must run before execute when freezeGateKey is set"
        ) {
          step.execute(TestContext(gateOpen = true), "core").void
        }
      }
  }

  test("compile - frozen per-item gate can narrow an absent key in both execute paths") {
    Ref.of[IO, List[String]](Nil).flatMap { events =>
      Ref.of[IO, Int](0).flatMap { narrowCalls =>
        val hook   = ItemHook(
          name = "publish-check",
          execute = (ctx, item) => events.update(_ :+ s"execute:$item").as(ctx)
        )
        val phases = Seq[LifecycleCompiler.Phase[
          TestConfig,
          TestContext,
          String
        ]](
          LifecycleCompiler.perItemHookPhase(
            phase = "before-publish",
            resolveHooks = _.itemHooks,
            gate = (_, _) => IO.pure(true),
            nameOf = (h: ItemHook) => h.name,
            executeOf = (h: ItemHook) => h.execute,
            validateOf = (h: ItemHook) => h.validate,
            freezeGateKey = Some((_, item) => item),
            narrowExecute = Some((_, item) => narrowCalls.update(_ + 1).as(item == "validated")),
            narrowOnMissingFrozenGate = true
          )
        )

        LifecycleCompiler
          .compile(TestConfig(itemHooks = Seq(hook)), phases)
          .flatMap { steps =>
            val step = ProcessStep
              .fold[TestContext, String, ProcessStep.PerItem[TestContext, String]](steps.head)(
                _ => fail("expected PerItem step"),
                identity
              )

            for {
              validated <- step.validate(TestContext(gateOpen = true), "validated")
              direct    <- executePerItem(step, validated, "introduced-direct", tracked = false)
              tracked   <- executePerItem(step, validated, "introduced-tracked", tracked = true)
              recorded  <- events.get
              calls     <- narrowCalls.get
            } yield {
              assertEquals(direct, validated)
              assertEquals(tracked, validated)
              assertEquals(recorded, Nil)
              assertEquals(calls, 2)
            }
          }
      }
    }
  }

  test("compile - cached false skips without evaluating the missing-key narrow") {
    Ref.of[IO, List[String]](Nil).flatMap { events =>
      Ref.of[IO, Int](0).flatMap { narrowCalls =>
        val hook   = ItemHook(
          name = "publish-check",
          execute = (ctx, item) => events.update(_ :+ s"execute:$item").as(ctx)
        )
        val phases = Seq[LifecycleCompiler.Phase[TestConfig, TestContext, String]](
          LifecycleCompiler.perItemHookPhase(
            phase = "before-publish",
            resolveHooks = _.itemHooks,
            gate = (_, _) => IO.pure(false),
            nameOf = (h: ItemHook) => h.name,
            executeOf = (h: ItemHook) => h.execute,
            validateOf = (h: ItemHook) => h.validate,
            freezeGateKey = Some((_, item) => item),
            narrowExecute = Some((_, _) => narrowCalls.update(_ + 1).as(true)),
            narrowOnMissingFrozenGate = true
          )
        )

        LifecycleCompiler
          .compile(TestConfig(itemHooks = Seq(hook)), phases)
          .flatMap { steps =>
            val step = ProcessStep
              .fold[TestContext, String, ProcessStep.PerItem[TestContext, String]](steps.head)(
                _ => fail("expected PerItem step"),
                identity
              )

            for {
              validated <- step.validate(TestContext(gateOpen = false), "core")
              _         <- executePerItem(step, validated, "core", tracked = false)
              _         <- executePerItem(step, validated, "core", tracked = true)
              recorded  <- events.get
              calls     <- narrowCalls.get
            } yield {
              assertEquals(recorded, Nil)
              assertEquals(calls, 0)
            }
          }
      }
    }
  }

  test("compile - cached true evaluates the narrow normally in both execute paths") {
    Ref.of[IO, List[String]](Nil).flatMap { events =>
      Ref.of[IO, Boolean](false).flatMap { allow =>
        Ref.of[IO, Int](0).flatMap { narrowCalls =>
          val hook   = ItemHook(
            name = "publish-check",
            execute = (ctx, item) => events.update(_ :+ s"execute:$item").as(ctx)
          )
          val phases = Seq[LifecycleCompiler.Phase[TestConfig, TestContext, String]](
            LifecycleCompiler.perItemHookPhase(
              phase = "before-publish",
              resolveHooks = _.itemHooks,
              gate = (_, _) => IO.pure(true),
              nameOf = (h: ItemHook) => h.name,
              executeOf = (h: ItemHook) => h.execute,
              validateOf = (h: ItemHook) => h.validate,
              freezeGateKey = Some((_, item) => item),
              narrowExecute = Some((_, _) => narrowCalls.update(_ + 1) *> allow.get),
              narrowOnMissingFrozenGate = true
            )
          )

          LifecycleCompiler
            .compile(TestConfig(itemHooks = Seq(hook)), phases)
            .flatMap { steps =>
              val step = ProcessStep
                .fold[TestContext, String, ProcessStep.PerItem[TestContext, String]](steps.head)(
                  _ => fail("expected PerItem step"),
                  identity
                )

              for {
                validated <- step.validate(TestContext(gateOpen = true), "core")
                _         <- executePerItem(step, validated, "core", tracked = false)
                _         <- executePerItem(step, validated, "core", tracked = true)
                _         <- allow.set(true)
                _         <- executePerItem(step, validated, "core", tracked = false)
                _         <- executePerItem(step, validated, "core", tracked = true)
                recorded  <- events.get
                calls     <- narrowCalls.get
              } yield {
                assertEquals(recorded, List("execute:core", "execute:core"))
                assertEquals(calls, 4)
              }
            }
        }
      }
    }
  }

  test("compile - absent-key narrow does not cache a synthetic false decision") {
    Ref.of[IO, Boolean](false).flatMap { allow =>
      val hook   = ItemHook(name = "publish-check")
      val phases = Seq[LifecycleCompiler.Phase[TestConfig, TestContext, String]](
        LifecycleCompiler.perItemHookPhase(
          phase = "before-publish",
          resolveHooks = _.itemHooks,
          gate = (_, _) => IO.pure(true),
          nameOf = (h: ItemHook) => h.name,
          executeOf = (h: ItemHook) => h.execute,
          validateOf = (h: ItemHook) => h.validate,
          freezeGateKey = Some((_, item) => item),
          narrowExecute = Some((_, _) => allow.get),
          narrowOnMissingFrozenGate = true
        )
      )

      LifecycleCompiler
        .compile(TestConfig(itemHooks = Seq(hook)), phases)
        .flatMap { steps =>
          val step     = ProcessStep
            .fold[TestContext, String, ProcessStep.PerItem[TestContext, String]](steps.head)(
              _ => fail("expected PerItem step"),
              identity
            )
          val expected =
            "Frozen gate decision missing for key 'introduced'; validate must run before execute when freezeGateKey is set"

          for {
            validated <- step.validate(TestContext(gateOpen = true), "validated")
            _         <- executePerItem(step, validated, "introduced", tracked = false)
            _         <- executePerItem(step, validated, "introduced-tracked", tracked = true)
            _         <- allow.set(true)
            direct    <- executePerItem(step, validated, "introduced", tracked = false).attempt
            tracked   <- executePerItem(
                           step,
                           validated,
                           "introduced-tracked",
                           tracked = true
                         ).attempt
          } yield {
            assertEquals(direct.left.map(_.getMessage), Left(expected))
            assertEquals(
              tracked.left.map(_.getMessage),
              Left(expected.replace("introduced'", "introduced-tracked'"))
            )
          }
        }
    }
  }

  test("compile - frozen per-item validation resolves its cache key and decision together") {
    Ref.of[IO, List[String]](Nil).flatMap { events =>
      val hook   = ItemHook(
        name = "publish-check",
        execute = (ctx, item) => events.update(_ :+ s"execute:$item").as(ctx),
        validate = (ctx, item) => events.update(_ :+ s"validate:${ctx.gateKey}:$item")
      )
      val phases = Seq[LifecycleCompiler.Phase[TestConfig, TestContext, String]](
        LifecycleCompiler.perItemHookPhase(
          phase = "before-publish",
          resolveHooks = _.itemHooks,
          gate = (_, _) => events.update(_ :+ "unexpected-gate").as(false),
          nameOf = (h: ItemHook) => h.name,
          executeOf = (h: ItemHook) => h.execute,
          validateOf = (h: ItemHook) => h.validate,
          freezeGateKey = Some((_, item) => item),
          freezeGateValidation = Some((ctx, _) =>
            events
              .update(_ :+ "validate-key-and-decision")
              .as(
                LifecycleCompiler.FrozenGateValidation(
                  ctx.copy(gateKey = "validated-context"),
                  "execute",
                  decision = true
                )
              )
          )
        )
      )

      LifecycleCompiler
        .compile(TestConfig(itemHooks = Seq(hook)), phases)
        .flatMap { steps =>
          val step = ProcessStep
            .fold[TestContext, String, ProcessStep.PerItem[TestContext, String]](steps.head)(
              _ => fail("expected PerItem step"),
              identity
            )

          for {
            validated <- step.validate(TestContext(gateOpen = true), "validation")
            direct    <- executePerItem(step, validated, "execute", tracked = false)
            tracked   <- executePerItem(step, validated, "execute", tracked = true)
            recorded  <- events.get
          } yield {
            assertEquals(validated.gateKey, "validated-context")
            assertEquals(direct, validated)
            assertEquals(tracked, validated)
            assertEquals(
              recorded,
              List(
                "validate-key-and-decision",
                "validate:validated-context:validation",
                "execute:execute",
                "execute:execute"
              )
            )
          }
        }
    }
  }

  test("compile - frozen per-item validation carries updated context when the gate is closed") {
    Ref.of[IO, List[String]](Nil).flatMap { events =>
      val hook   = ItemHook(
        name = "publish-check",
        execute = (ctx, _) => events.update(_ :+ "unexpected-execute").as(ctx),
        validate = (_, _) => events.update(_ :+ "unexpected-validate")
      )
      val phases = Seq[LifecycleCompiler.Phase[TestConfig, TestContext, String]](
        LifecycleCompiler.perItemHookPhase(
          phase = "before-publish",
          resolveHooks = _.itemHooks,
          gate = (_, _) => events.update(_ :+ "unexpected-gate").as(true),
          nameOf = (h: ItemHook) => h.name,
          executeOf = (h: ItemHook) => h.execute,
          validateOf = (h: ItemHook) => h.validate,
          freezeGateKey = Some((_, item) => item),
          freezeGateValidation = Some((ctx, _) =>
            events
              .update(_ :+ "validate-key-and-decision")
              .as(
                LifecycleCompiler.FrozenGateValidation(
                  ctx.copy(gateKey = "carried-context"),
                  "execute",
                  decision = false
                )
              )
          )
        )
      )

      LifecycleCompiler
        .compile(TestConfig(itemHooks = Seq(hook)), phases)
        .flatMap { steps =>
          val step = ProcessStep
            .fold[TestContext, String, ProcessStep.PerItem[TestContext, String]](steps.head)(
              _ => fail("expected PerItem step"),
              identity
            )

          for {
            validated <- step.validate(TestContext(gateOpen = true), "validation")
            direct    <- executePerItem(step, validated, "execute", tracked = false)
            tracked   <- executePerItem(step, validated, "execute", tracked = true)
            recorded  <- events.get
          } yield {
            assertEquals(validated.gateKey, "carried-context")
            assertEquals(direct, validated)
            assertEquals(tracked, validated)
            assertEquals(recorded, List("validate-key-and-decision"))
          }
        }
    }
  }
}

object LifecycleCompilerSpec {

  final case class TestConfig(
      singleHooks: Seq[SingleHook] = Nil,
      itemHooks: Seq[ItemHook] = Nil
  )

  final case class TestContext(gateOpen: Boolean, gateKey: String = "default")

  final case class SingleHook(
      name: String,
      execute: TestContext => IO[TestContext] = ctx => IO.pure(ctx),
      validate: TestContext => IO[Unit] = _ => IO.unit
  )

  final case class ItemHook(
      name: String,
      execute: (TestContext, String) => IO[TestContext] = (ctx, _) => IO.pure(ctx),
      validate: (TestContext, String) => IO[Unit] = (_, _) => IO.unit
  )
}
