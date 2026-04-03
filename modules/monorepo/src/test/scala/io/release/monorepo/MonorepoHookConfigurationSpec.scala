package io.release.monorepo

import cats.effect.IO
import munit.FunSuite

class MonorepoHookConfigurationSpec extends FunSuite {

  test("MonorepoHookConfiguration.empty matches the neutral constructor defaults") {
    assertEquals(MonorepoHookConfiguration.empty, MonorepoHookConfiguration())
    assert(!MonorepoHookConfiguration.empty.hasCustomizations)
  }

  test("MonorepoHookConfiguration.mergeWith keeps policy conjunction and hook append order") {
    val left  = MonorepoHookConfiguration(
      enablePublish = false,
      beforeSelectionHooks = Seq(MonorepoGlobalHookIO.action("left-global")(_ => IO.unit))
    )
    val right = MonorepoHookConfiguration(
      enableRunTests = false,
      beforeTagHooks = Seq(MonorepoProjectHookIO.action("right-project")((_, _) => IO.unit))
    )

    val merged = left.mergeWith(right)

    assert(!merged.enablePublish)
    assert(!merged.enableRunTests)
    assertEquals(merged.beforeSelectionHooks.map(_.name), Seq("left-global"))
    assertEquals(merged.beforeTagHooks.map(_.name), Seq("right-project"))
  }
}
