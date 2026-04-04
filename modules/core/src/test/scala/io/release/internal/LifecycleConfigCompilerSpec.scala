package io.release.internal

import cats.effect.IO
import io.release.TestSupport
import munit.CatsEffectSuite
import sbt.*

class LifecycleConfigCompilerSpec extends CatsEffectSuite {

  test("defaultSettings - deduplicate repeated policy bindings") {
    IO {
      val defaultSettingLabels =
        LifecycleConfigCompiler
          .defaultSettings(LifecycleConfigCompilerSpec.phases)
          .map(_.key.key.label)

      assertEquals(
        defaultSettingLabels,
        Seq(
          LifecycleConfigCompilerSpec.EnablePublishKey.key.label,
          LifecycleConfigCompilerSpec.BeforeHooksKey.key.label,
          LifecycleConfigCompilerSpec.AfterHooksKey.key.label
        )
      )
    }
  }

  test("resolve - deduplicate repeated policy bindings while reading settings") {
    stateResource(
      "lifecycle-config-compiler-resolve",
      Seq(
        LifecycleConfigCompilerSpec.EnablePublishKey := false,
        LifecycleConfigCompilerSpec.BeforeHooksKey   := Seq("before"),
        LifecycleConfigCompilerSpec.AfterHooksKey    := Seq("after")
      )
    ).use { state =>
      IO {
        val config = LifecycleConfigCompiler.resolve(
          state,
          LifecycleConfigCompilerSpec.TestConfig(),
          LifecycleConfigCompilerSpec.phases
        )

        assert(!config.enablePublish)
        assertEquals(config.beforeHooks, Seq("before"))
        assertEquals(config.afterHooks, Seq("after"))
        assertEquals(config.policyResolveCount, 1)
      }
    }
  }

  test("merge - combine policies with logical and and append hook buckets in order") {
    val left  = LifecycleConfigCompilerSpec.TestConfig(
      enablePublish = true,
      beforeHooks = Seq("left-before"),
      afterHooks = Seq("left-after")
    )
    val right = LifecycleConfigCompilerSpec.TestConfig(
      enablePublish = false,
      beforeHooks = Seq("right-before"),
      afterHooks = Seq("right-after")
    )

    IO {
      val merged = LifecycleConfigCompiler.merge(left, right, LifecycleConfigCompilerSpec.phases)

      assert(!merged.enablePublish)
      assertEquals(merged.beforeHooks, Seq("left-before", "right-before"))
      assertEquals(merged.afterHooks, Seq("left-after", "right-after"))
    }
  }

  test("hasCustomizations - detect disabled policies and non-empty hook buckets") {
    IO {
      assert(
        !LifecycleConfigCompiler.hasCustomizations(
          LifecycleConfigCompilerSpec.TestConfig(),
          LifecycleConfigCompilerSpec.phases
        )
      )
      assert(
        LifecycleConfigCompiler.hasCustomizations(
          LifecycleConfigCompilerSpec.TestConfig(enablePublish = false),
          LifecycleConfigCompilerSpec.phases
        )
      )
      assert(
        LifecycleConfigCompiler.hasCustomizations(
          LifecycleConfigCompilerSpec.TestConfig(beforeHooks = Seq("before")),
          LifecycleConfigCompilerSpec.phases
        )
      )
    }
  }

  private def stateResource(
      prefix: String,
      settings: Seq[Setting[?]]
  ) =
    TestSupport.tempDirResource(prefix).evalMap { dir =>
      IO.blocking(
        TestSupport.loadedState(
          dir,
          Seq(
            Project("root", dir).settings(
              (LifecycleConfigCompiler
                .defaultSettings(LifecycleConfigCompilerSpec.phases) ++ settings)*
            )
          ),
          currentProjectId = Some("root")
        )
      )
    }
}

object LifecycleConfigCompilerSpec {

  final case class TestConfig(
      enablePublish: Boolean = true,
      beforeHooks: Seq[String] = Nil,
      afterHooks: Seq[String] = Nil,
      policyResolveCount: Int = 0
  )

  final case class TestContext(name: String)

  val EnablePublishKey: SettingKey[Boolean] =
    SettingKey[Boolean](
      "lifecycleConfigCompilerSpecEnablePublish",
      "Lifecycle config compiler spec publish toggle"
    )

  val BeforeHooksKey: SettingKey[Seq[String]] =
    SettingKey[Seq[String]](
      "lifecycleConfigCompilerSpecBeforeHooks",
      "Lifecycle config compiler spec single hook bucket"
    )

  val AfterHooksKey: SettingKey[Seq[String]] =
    SettingKey[Seq[String]](
      "lifecycleConfigCompilerSpecAfterHooks",
      "Lifecycle config compiler spec per-item hook bucket"
    )

  private val PublishBinding = LifecycleConfigCompiler.policyBinding[TestConfig](
    key = EnablePublishKey,
    get = _.enablePublish,
    updated = (config, value) =>
      config.copy(
        enablePublish = value,
        policyResolveCount = config.policyResolveCount + 1
      )
  )

  private val BeforeHooksBinding = LifecycleConfigCompiler.hookBinding[TestConfig, String](
    key = BeforeHooksKey,
    get = _.beforeHooks,
    updated = (config, hooks) => config.copy(beforeHooks = hooks)
  )

  private val AfterHooksBinding = LifecycleConfigCompiler.hookBinding[TestConfig, String](
    key = AfterHooksKey,
    get = _.afterHooks,
    updated = (config, hooks) => config.copy(afterHooks = hooks)
  )

  val phases: Seq[LifecycleCompiler.Phase[TestConfig, TestContext, String]] = Seq(
    LifecycleCompiler.singleBuiltIn(
      step = ProcessStep.Single[TestContext](
        name = "publish",
        execute = ctx => IO.pure(ctx)
      ),
      enabled = _.enablePublish,
      configBindings = Seq(PublishBinding)
    ),
    LifecycleCompiler.singleHookPhase(
      phase = "before-publish",
      resolveHooks = _.beforeHooks,
      gate = _ => IO.pure(true),
      nameOf = (hook: String) => hook,
      executeOf = (_: String) => (ctx: TestContext) => IO.pure(ctx),
      validateOf = (_: String) => (_: TestContext) => IO.unit,
      enabled = _.enablePublish,
      configBindings = Seq(BeforeHooksBinding, PublishBinding)
    ),
    LifecycleCompiler.perItemHookPhase(
      phase = "after-publish",
      resolveHooks = _.afterHooks,
      gate = (_, _) => IO.pure(true),
      nameOf = (hook: String) => hook,
      executeOf = (_: String) => (ctx: TestContext, _: String) => IO.pure(ctx),
      validateOf = (_: String) => (_: TestContext, _: String) => IO.unit,
      enabled = _.enablePublish,
      configBindings = Seq(AfterHooksBinding, PublishBinding)
    )
  )
}
