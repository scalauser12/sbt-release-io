package io.release.core.internal

import io.release.ReleasePluginIO
import munit.FunSuite

class CoreDefaultSettingsSpec extends FunSuite {

  test("pluginDefaultSettings include representative project-scoped defaults") {
    val labels = CoreDefaultSettings.pluginDefaultSettings.map(_.key.key.label).toSet

    assert(labels.contains(ReleasePluginIO.autoImport.releaseIOVersioningReadVersion.key.label))
    assert(labels.contains(ReleasePluginIO.autoImport.releaseIOVcsTagName.key.label))
  }

  test("buildDefaultSettings include representative build-scoped defaults") {
    val labels = CoreDefaultSettings.buildDefaultSettings.map(_.key.key.label).toSet

    assert(labels.contains(ReleasePluginIO.autoImport.releaseIOBehaviorCrossBuild.key.label))
    assert(labels.contains(ReleasePluginIO.autoImport.releaseIOPolicyEnablePush.key.label))
    assert(labels.contains(ReleasePluginIO.autoImport.releaseIOPublishChecks.key.label))
  }

  test("buildDefaultSettings include each lifecycle-derived default exactly once") {
    val counts = CoreDefaultSettings.buildDefaultSettings
      .groupBy(_.key.key.label)
      .map { case (label, settings) => label -> settings.size }
      .toMap

    CoreLifecycle.configDefaultSettings.map(_.key.key.label).distinct.foreach { label =>
      assertEquals(counts.getOrElse(label, 0), 1, label)
    }
  }
}
