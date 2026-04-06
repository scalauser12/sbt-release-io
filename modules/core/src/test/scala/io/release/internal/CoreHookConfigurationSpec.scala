package io.release.internal

import cats.effect.IO
import io.release.ReleaseHookIO
import io.release.ReleaseIO
import io.release.TestSupport
import munit.CatsEffectSuite
import sbt.*

class CoreHookConfigurationSpec extends CatsEffectSuite {

  test("defaultSettings - expose each lifecycle-derived setting key exactly once") {
    IO {
      val labels = CoreHookConfiguration.defaultSettings.map(_.key.key.label)

      assertEquals(labels, labels.distinct)
      assertEquals(labels.sorted, CoreLifecycleSlots.slots.map(_.keyLabel).sorted)
    }
  }

  test("resolve - read lifecycle policies and hook buckets from state") {
    stateResource(
      "core-hook-configuration-resolve",
      Seq(
        ReleaseIO.releaseIOPolicyEnablePublish := false,
        ReleaseIO.releaseIOHooksBeforeTag      := Seq(
          ReleaseHookIO.action("before-tag")(_ => IO.unit)
        ),
        ReleaseIO.releaseIOHooksAfterPush      := Seq(
          ReleaseHookIO.action("after-push")(_ => IO.unit)
        )
      )
    ).use { state =>
      IO {
        val config = CoreHookConfiguration.resolve(state)

        assert(!config.enablePublish)
        assertEquals(config.beforeTagHooks.map(_.name), Seq("before-tag"))
        assertEquals(config.afterPushHooks.map(_.name), Seq("after-push"))
      }
    }
  }

  test("merge - combine policies with logical and and append hook buckets in order") {
    val left  = CoreHookConfiguration.empty.copy(
      enablePublish = true,
      beforeTagHooks = Seq(ReleaseHookIO.action("left-before-tag")(_ => IO.unit)),
      afterPushHooks = Seq(ReleaseHookIO.action("left-after-push")(_ => IO.unit))
    )
    val right = CoreHookConfiguration.empty.copy(
      enablePublish = false,
      beforeTagHooks = Seq(ReleaseHookIO.action("right-before-tag")(_ => IO.unit)),
      afterPushHooks = Seq(ReleaseHookIO.action("right-after-push")(_ => IO.unit))
    )

    IO {
      val merged = CoreHookConfiguration.merge(left, right)

      assert(!merged.enablePublish)
      assertEquals(
        merged.beforeTagHooks.map(_.name),
        Seq("left-before-tag", "right-before-tag")
      )
      assertEquals(
        merged.afterPushHooks.map(_.name),
        Seq("left-after-push", "right-after-push")
      )
    }
  }

  test("hasCustomizations - detect disabled policies and non-empty hook buckets") {
    IO {
      assert(!CoreHookConfiguration.hasCustomizations(CoreHookConfiguration.empty))
      assert(
        CoreHookConfiguration.hasCustomizations(
          CoreHookConfiguration.empty.copy(enablePublish = false)
        )
      )
      assert(
        CoreHookConfiguration.hasCustomizations(
          CoreHookConfiguration.empty.copy(
            beforeTagHooks = Seq(ReleaseHookIO.action("before-tag")(_ => IO.unit))
          )
        )
      )
    }
  }

  test("slot catalog validation fails fast on duplicate ids") {
    IO {
      val err = intercept[IllegalStateException] {
        LifecycleCatalogSupport.validateUniqueSlots(
          "core",
          Vector[CoreConfigSlot](
            CorePolicySlots.enablePublish,
            CorePolicySlots.enablePublish
          )
        )(_.id, _.keyLabel)
      }

      assert(err.getMessage.contains("core lifecycle slot catalog"))
      assert(err.getMessage.contains(CorePolicySlots.enablePublish.id))
      assert(err.getMessage.contains(CorePolicySlots.enablePublish.keyLabel))
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
            Project("root", dir).settings((CoreHookConfiguration.defaultSettings ++ settings)*)
          ),
          currentProjectId = Some("root")
        )
      )
    }
}
