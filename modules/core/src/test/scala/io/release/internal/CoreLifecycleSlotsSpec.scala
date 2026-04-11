package io.release.core.internal

import io.release.ReleasePluginIO
import io.release.ReleaseContext
import io.release.core.internal.steps.ReleaseSteps
import io.release.runtime.engine.LifecycleCompiler
import munit.FunSuite
import sbt.SettingKey

class CoreLifecycleSlotsSpec extends FunSuite {

  test("phases - preserve compiled hook phase and built-in step order") {
    assertEquals(
      hookPhaseNames(CoreLifecycle.phases),
      CoreLifecycleSlotsSpec.expectedHookPhases
    )
    assertEquals(
      builtInStepNames(CoreLifecycle.phases),
      ReleaseSteps.defaults.map(_.name)
    )
  }

  test("phases - default settings cover all expected keys") {
    val settingKeys =
      CoreLifecycle.configDefaultSettings.map(_.key.key.label).sorted

    assertEquals(settingKeys, CoreLifecycleSlotsSpec.expectedSettingKeys.toSeq.sorted)
  }

  private def hookPhaseNames(
      phases: Seq[
        LifecycleCompiler.Phase[
          CoreHookConfiguration,
          ReleaseContext,
          Nothing
        ]
      ]
  ): Seq[String] =
    phases.flatMap(_.phaseName)

  private def builtInStepNames(
      phases: Seq[
        LifecycleCompiler.Phase[
          CoreHookConfiguration,
          ReleaseContext,
          Nothing
        ]
      ]
  ): Seq[String] =
    LifecycleCompiler.defaultsSingle(phases).map(_.name)
}

object CoreLifecycleSlotsSpec {
  private def keyLabel[A](key: SettingKey[A]): String = key.key.label

  lazy val expectedSettingKeys: Set[String] = {
    val autoImport      = ReleasePluginIO.autoImport
    val autoImportClass = autoImport.getClass

    autoImportClass.getMethods.iterator
      .filter(method =>
        method.getDeclaringClass == autoImportClass &&
          method.getParameterCount == 0 &&
          classOf[SettingKey[?]].isAssignableFrom(method.getReturnType)
      )
      .map(method => keyLabel(method.invoke(autoImport).asInstanceOf[SettingKey[?]]))
      .filter(label => label.startsWith("releaseIOPolicy") || label.startsWith("releaseIOHooks"))
      .toSet
  }

  val expectedHookPhases: Seq[String] = Seq(
    "after-clean-check",
    "before-version-resolution",
    "after-version-resolution",
    "before-release-version-write",
    "after-release-version-write",
    "before-release-commit",
    "after-release-commit",
    "before-tag",
    "after-tag",
    "before-publish",
    "after-publish",
    "before-next-version-write",
    "after-next-version-write",
    "before-next-commit",
    "after-next-commit",
    "before-push",
    "after-push"
  )
}
