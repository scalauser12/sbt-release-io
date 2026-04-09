package io.release.core.internal

import io.release.ReleasePluginIO
import munit.FunSuite

class CoreDefaultSettingsSpec extends FunSuite {

  test("pluginDefaultSettings include representative core defaults") {
    val labels = CoreDefaultSettings.pluginDefaultSettings.map(_.key.key.label).toSet

    assert(labels.contains(ReleasePluginIO.autoImport.releaseIOBehaviorCrossBuild.key.label))
    assert(labels.contains(ReleasePluginIO.autoImport.releaseIOPolicyEnablePush.key.label))
    assert(labels.contains(ReleasePluginIO.autoImport.releaseIOVersioningFile.key.label))
    assert(labels.contains(ReleasePluginIO.autoImport.releaseIOVcsTagName.key.label))
    assert(labels.contains(ReleasePluginIO.autoImport.releaseIOPublishAction.key.label))
  }

  test("pluginDefaultSettings include each lifecycle-derived default exactly once") {
    val counts = CoreDefaultSettings.pluginDefaultSettings
      .groupBy(_.key.key.label)
      .map { case (label, settings) => label -> settings.size }
      .toMap

    CoreLifecycle.configDefaultSettings.map(_.key.key.label).distinct.foreach { label =>
      assertEquals(counts.getOrElse(label, 0), 1, label)
    }
  }
}
