package io.release.monorepo

import io.release.monorepo.internal.*
import munit.FunSuite

class MonorepoDefaultSettingsSpec extends FunSuite {

  test("pluginDefaultSettings include representative project-scoped defaults") {
    val labels = MonorepoDefaultSettings.pluginDefaultSettings.map(_.key.key.label).toSet

    assert(
      labels.contains(MonorepoReleasePlugin.autoImport.releaseIOMonorepoSelectionProjects.key.label)
    )
  }

  test("buildDefaultSettings include representative build-scoped defaults") {
    val labels = MonorepoDefaultSettings.buildDefaultSettings.map(_.key.key.label).toSet

    assert(
      labels.contains(
        MonorepoReleasePlugin.autoImport.releaseIOMonorepoBehaviorCrossBuild.key.label
      )
    )
    assert(
      labels.contains(
        MonorepoReleasePlugin.autoImport.releaseIOMonorepoPolicyEnablePublish.key.label
      )
    )
    assert(
      labels.contains(MonorepoReleasePlugin.autoImport.releaseIOMonorepoVersioningFile.key.label)
    )
    assert(
      labels.contains(MonorepoReleasePlugin.autoImport.releaseIOMonorepoPublishChecks.key.label)
    )
  }

  test("pluginDefaultSettings exclude defaults that must flow from ThisBuild") {
    val labels = MonorepoDefaultSettings.pluginDefaultSettings.map(_.key.key.label).toSet

    // Project-scoped duplicates would shadow `ThisBuild / ...` overrides because
    // project scope wins over ThisBuild on the project axis. Keep these out of
    // the project-scope defaults entirely.
    assert(
      !labels.contains(
        MonorepoReleasePlugin.autoImport.releaseIOMonorepoBehaviorCrossBuild.key.label
      )
    )
    assert(
      !labels.contains(
        MonorepoReleasePlugin.autoImport.releaseIOMonorepoPolicyEnablePush.key.label
      )
    )
    assert(
      !labels.contains(MonorepoReleasePlugin.autoImport.releaseIOMonorepoPublishChecks.key.label)
    )
  }

  test("buildDefaultSettings include each lifecycle-derived default exactly once") {
    val counts =
      MonorepoDefaultSettings.buildDefaultSettings
        .groupBy(_.key.key.label)
        .map { case (label, settings) => label -> settings.size }
        .toMap

    MonorepoLifecycle.configDefaultSettings.map(_.key.key.label).distinct.foreach { label =>
      assertEquals(counts.getOrElse(label, 0), 1, label)
    }
  }
}
