package io.release.monorepo

import io.release.monorepo.internal.*

import cats.effect.IO
import io.release.TestSupport
import io.release.runtime.engine.LifecycleCatalogSupport
import munit.CatsEffectSuite
import sbt.*

class MonorepoHookConfigurationSpec extends CatsEffectSuite {

  test("defaultSettings - expose each lifecycle-derived setting key exactly once") {
    IO {
      val labels = MonorepoHookConfiguration.defaultSettings.map(_.key.key.label)

      assertEquals(labels, labels.distinct)
      assertEquals(labels.sorted, MonorepoLifecycle.slots.map(_.keyLabel).sorted)
    }
  }

  test("resolve - read lifecycle policies and hook buckets from state") {
    stateResource(
      "monorepo-hook-configuration-resolve",
      Seq(
        MonorepoReleasePlugin.autoImport.releaseIOMonorepoPolicyEnablePublish        := false,
        MonorepoReleasePlugin.autoImport.releaseIOMonorepoHooksBeforeSelection       := Seq(
          MonorepoGlobalHookIO.action("before-selection")(_ => IO.unit)
        ),
        MonorepoReleasePlugin.autoImport.releaseIOMonorepoHooksAfterNextVersionWrite := Seq(
          MonorepoProjectHookIO.action("after-next-version")((_, _) => IO.unit)
        )
      )
    ).use { state =>
      IO {
        val config = MonorepoHookConfiguration.resolve(state)

        assert(!config.enablePublish)
        assertEquals(config.beforeSelectionHooks.map(_.name), Seq("before-selection"))
        assertEquals(
          config.afterNextVersionWriteHooks.map(_.name),
          Seq("after-next-version")
        )
      }
    }
  }

  test("merge - combine policies with logical and and append hook buckets in order") {
    val left  = MonorepoHookConfiguration.empty.copy(
      enablePublish = true,
      beforeSelectionHooks =
        Seq(MonorepoGlobalHookIO.action("left-before-selection")(_ => IO.unit)),
      afterNextVersionWriteHooks =
        Seq(MonorepoProjectHookIO.action("left-after-next-version")((_, _) => IO.unit))
    )
    val right = MonorepoHookConfiguration.empty.copy(
      enablePublish = false,
      beforeSelectionHooks =
        Seq(MonorepoGlobalHookIO.action("right-before-selection")(_ => IO.unit)),
      afterNextVersionWriteHooks =
        Seq(MonorepoProjectHookIO.action("right-after-next-version")((_, _) => IO.unit))
    )

    IO {
      val merged = MonorepoHookConfiguration.merge(left, right)

      assert(!merged.enablePublish)
      assertEquals(
        merged.beforeSelectionHooks.map(_.name),
        Seq("left-before-selection", "right-before-selection")
      )
      assertEquals(
        merged.afterNextVersionWriteHooks.map(_.name),
        Seq("left-after-next-version", "right-after-next-version")
      )
    }
  }

  test("hasCustomizations - detect disabled policies and non-empty hook buckets") {
    IO {
      assert(!MonorepoHookConfiguration.hasCustomizations(MonorepoHookConfiguration.empty))
      assert(
        MonorepoHookConfiguration.hasCustomizations(
          MonorepoHookConfiguration.empty.copy(enablePublish = false)
        )
      )
      assert(
        MonorepoHookConfiguration.hasCustomizations(
          MonorepoHookConfiguration.empty.copy(
            beforeSelectionHooks =
              Seq(MonorepoGlobalHookIO.action("before-selection")(_ => IO.unit))
          )
        )
      )
    }
  }

  test("slot catalog validation fails fast on duplicate ids") {
    IO {
      val err = intercept[IllegalStateException] {
        LifecycleCatalogSupport.validateUniqueSlots(
          "monorepo",
          Vector[MonorepoConfigSlot](
            MonorepoPolicySlots.enablePublish,
            MonorepoPolicySlots.enablePublish
          )
        )(_.id, _.keyLabel)
      }

      assert(err.getMessage.contains("monorepo lifecycle slot catalog"))
      assert(err.getMessage.contains(MonorepoPolicySlots.enablePublish.id))
      assert(err.getMessage.contains(MonorepoPolicySlots.enablePublish.keyLabel))
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
              (MonorepoHookConfiguration.defaultSettings ++ settings)*
            )
          ),
          currentProjectId = Some("root")
        )
      )
    }
}
