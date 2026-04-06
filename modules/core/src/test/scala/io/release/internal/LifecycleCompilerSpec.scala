package io.release.internal

import cats.effect.IO
import cats.effect.Ref
import io.release.internal.LifecycleCompilerSpec.ItemHook
import io.release.internal.LifecycleCompilerSpec.SingleHook
import io.release.internal.LifecycleCompilerSpec.TestConfig
import io.release.internal.LifecycleCompilerSpec.TestContext
import munit.CatsEffectSuite

class LifecycleCompilerSpec extends CatsEffectSuite {

  private def singleHookPhase[I](
      phase: String,
      resolveHooks: TestConfig => Seq[SingleHook],
      gate: TestContext => IO[Boolean],
      crossBuild: Boolean = false,
      cachedGate: Option[LifecycleCompiler.CachedSingleGate[TestContext, String]] = None
  ): LifecycleCompiler.Phase[TestConfig, TestContext, I] =
    LifecycleCompiler.singleHookPhase(
      phase = phase,
      resolveHooks = resolveHooks,
      gate = gate,
      nameOf = (hook: SingleHook) => hook.name,
      executeOf = (hook: SingleHook) => hook.execute,
      validateOf = (hook: SingleHook) => hook.validate,
      crossBuild = crossBuild,
      cachedGate = cachedGate
    )

  private def itemHookPhase(
      phase: String,
      resolveHooks: TestConfig => Seq[ItemHook],
      gate: (TestContext, String) => IO[Boolean],
      crossBuild: Boolean = false,
      cachedGate: Option[LifecycleCompiler.CachedItemGate[TestContext, String, String]] = None
  ): LifecycleCompiler.Phase[TestConfig, TestContext, String] =
    LifecycleCompiler.perItemHookPhase(
      phase = phase,
      resolveHooks = resolveHooks,
      gate = gate,
      nameOf = (hook: ItemHook) => hook.name,
      executeOf = (hook: ItemHook) => hook.execute,
      validateOf = (hook: ItemHook) => hook.validate,
      crossBuild = crossBuild,
      cachedGate = cachedGate
    )

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

    val compiled = LifecycleCompiler.compileSingle(
      TestConfig(
        singleHooks = Seq(
          SingleHook("resolve"),
          SingleHook("confirm")
        )
      ),
      phases
    )

    assertEquals(compiled.map(_.name), Seq("before-version:resolve", "before-version:confirm"))
    assert(compiled.forall(_.enableCrossBuild))
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

    val compiled = LifecycleCompiler
      .compile(
        TestConfig(
          itemHooks = Seq(
            ItemHook("prepare"),
            ItemHook("verify")
          )
        ),
        phases
      )
      .map(_.asInstanceOf[ProcessStep.PerItem[TestContext, String]])

    assertEquals(compiled.map(_.name), Seq("before-publish:prepare", "before-publish:verify"))
    assert(compiled.forall(_.enableCrossBuild))
  }

  test("compileSingle - cached single-context gates reuse validation decisions during execute") {
    Ref.of[IO, Map[String, Boolean]](Map.empty).flatMap { decisions =>
      Ref.of[IO, Int](0).flatMap { gateCalls =>
        Ref.of[IO, List[String]](Nil).flatMap { events =>
          val cachedGate = LifecycleCompiler.CachedSingleGate[TestContext, String](
            tokenForIndex = hookIndex => s"before-publish:$hookIndex",
            resolveDecision = (_, token, fallback) =>
              decisions.get.flatMap(_.get(token) match {
                case Some(decision) => IO.pure(decision)
                case None           => fallback
              }),
            snapshotDecision = (ctx, token, evaluateGate) =>
              evaluateGate(ctx)
                .flatTap(decision => decisions.update(_.updated(token, decision)))
                .map(decision => (ctx, decision))
          )
          val hook       = SingleHook(
            name = "publish-check",
            execute = ctx => events.update(_ :+ "execute").as(ctx),
            validate = _ => events.update(_ :+ "validate")
          )
          val phases     = Seq[LifecycleCompiler.Phase[TestConfig, TestContext, Nothing]](
            singleHookPhase(
              phase = "before-publish",
              resolveHooks = _.singleHooks,
              gate = ctx => gateCalls.update(_ + 1).as(ctx.gateOpen),
              cachedGate = Some(cachedGate)
            )
          )

          val step = LifecycleCompiler
            .compileSingle(TestConfig(singleHooks = Seq(hook)), phases)
            .head

          for {
            validated <- step.threadedValidation(TestContext(gateOpen = true))
            _         <- step.execute(validated.copy(gateOpen = false))
            recorded  <- events.get
            calls     <- gateCalls.get
          } yield {
            assertEquals(recorded, List("validate", "execute"))
            assertEquals(calls, 1)
          }
        }
      }
    }
  }

  test("compile - cached per-item gates reuse validation decisions during execute") {
    Ref.of[IO, Map[(String, String), Boolean]](Map.empty).flatMap { decisions =>
      Ref.of[IO, Int](0).flatMap { gateCalls =>
        Ref.of[IO, List[String]](Nil).flatMap { events =>
          val cachedGate = LifecycleCompiler.CachedItemGate[TestContext, String, String](
            tokenForIndex = hookIndex => s"before-publish:$hookIndex",
            resolveDecision = (_, token, item, fallback) =>
              decisions.get.flatMap(_.get(token -> item) match {
                case Some(decision) => IO.pure(decision)
                case None           => fallback
              }),
            snapshotDecision = (ctx, token, item, evaluateGate) =>
              evaluateGate(ctx, item)
                .flatTap(decision => decisions.update(_.updated(token -> item, decision)))
                .map(decision => (ctx, decision))
          )
          val hook       = ItemHook(
            name = "publish-check",
            execute = (ctx, _) => events.update(_ :+ "execute").as(ctx),
            validate = (_, _) => events.update(_ :+ "validate")
          )
          val phases     = Seq[LifecycleCompiler.Phase[TestConfig, TestContext, String]](
            itemHookPhase(
              phase = "before-publish",
              resolveHooks = _.itemHooks,
              gate = (ctx, _) => gateCalls.update(_ + 1).as(ctx.gateOpen),
              cachedGate = Some(cachedGate)
            )
          )
          val step       = LifecycleCompiler
            .compile(TestConfig(itemHooks = Seq(hook)), phases)
            .head
            .asInstanceOf[ProcessStep.PerItem[TestContext, String]]

          for {
            validated <- step.threadedValidation(TestContext(gateOpen = true), "core")
            _         <- step.execute(validated.copy(gateOpen = false), "core")
            recorded  <- events.get
            calls     <- gateCalls.get
          } yield {
            assertEquals(recorded, List("validate", "execute"))
            assertEquals(calls, 1)
          }
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

  final case class TestContext(gateOpen: Boolean)

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
