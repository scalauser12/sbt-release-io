package io.release.runtime.engine

import cats.effect.IO
import cats.effect.Ref
import munit.CatsEffectSuite

import LifecycleCompilerSpec.{ItemHook, SingleHook, TestConfig, TestContext}

class LifecycleCompilerSpec extends CatsEffectSuite {

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
      LifecycleCompiler.singleBuiltIn(singleStep),
      singleHookPhase(
        phase = "before-publish",
        resolveHooks = _.singleHooks,
        gate = _ => IO.pure(true)
      ),
      LifecycleCompiler.perItemBuiltIn(itemStep),
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
              freezeGate = true,
              gateKey = Some(_ => "publish")
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
              freezeGate = true,
              gateKey = Some(_.gateKey)
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

  test("singleHookPhase - require an explicit gateKey when freezing is enabled") {
    val err = intercept[IllegalArgumentException] {
      LifecycleCompiler.singleHookPhase[TestConfig, TestContext, Nothing, SingleHook](
        phase = "before-publish",
        resolveHooks = _.singleHooks,
        gate = _ => IO.pure(true),
        nameOf = (hook: SingleHook) => hook.name,
        executeOf = (hook: SingleHook) => hook.execute,
        validateOf = (hook: SingleHook) => hook.validate,
        freezeGate = true
      )
    }

    assertEquals(
      err.getMessage,
      "requirement failed: phase 'before-publish' requires an explicit stable gateKey when freezeGate = true"
    )
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
          freezeGate = true,
          gateKey = Some(_ => "core")
        )
      )

    LifecycleCompiler
      .compileSingle(TestConfig(singleHooks = Seq(hook)), phases)
      .flatMap { steps =>
        interceptMessageIO[IllegalStateException](
          "Frozen gate decision missing for key 'core'; validate must run before execute when freezeGate = true"
        ) {
          steps.head.execute(TestContext(gateOpen = true)).void
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
            freezeGate = true,
            gateKey = Some((_, item) => item)
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

  test("perItemHookPhase - require an explicit gateKey when freezing is enabled") {
    val err = intercept[IllegalArgumentException] {
      LifecycleCompiler.perItemHookPhase[TestConfig, TestContext, String, ItemHook](
        phase = "before-publish",
        resolveHooks = _.itemHooks,
        gate = (_, _) => IO.pure(true),
        nameOf = (hook: ItemHook) => hook.name,
        executeOf = (hook: ItemHook) => hook.execute,
        validateOf = (hook: ItemHook) => hook.validate,
        freezeGate = true
      )
    }

    assertEquals(
      err.getMessage,
      "requirement failed: phase 'before-publish' requires an explicit stable gateKey when freezeGate = true"
    )
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
        freezeGate = true,
        gateKey = Some((_, item) => item)
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
          "Frozen gate decision missing for key 'core'; validate must run before execute when freezeGate = true"
        ) {
          step.execute(TestContext(gateOpen = true), "core").void
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
