package io.release.internal

import io.release.ReleaseHookIO
import munit.FunSuite

class CoreHookConfigurationSpec extends FunSuite {

  test("CoreHookConfiguration.empty matches the neutral constructor defaults") {
    assertEquals(CoreHookConfiguration.empty, CoreHookConfiguration())
    assert(!CoreHookConfiguration.empty.hasCustomizations)
  }

  test("CoreHookConfiguration.mergeWith keeps policy conjunction and hook append order") {
    val left  = CoreHookConfiguration(
      enablePublish = false,
      beforeTagHooks = Seq(ReleaseHookIO.action("left-before-tag")(_ => cats.effect.IO.unit))
    )
    val right = CoreHookConfiguration(
      enableRunTests = false,
      beforeTagHooks = Seq(ReleaseHookIO.action("right-before-tag")(_ => cats.effect.IO.unit))
    )

    val merged = left.mergeWith(right)

    assert(!merged.enablePublish)
    assert(!merged.enableRunTests)
    assertEquals(
      merged.beforeTagHooks.map(_.name),
      Seq("left-before-tag", "right-before-tag")
    )
  }
}
