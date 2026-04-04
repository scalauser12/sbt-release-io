package io.release.monorepo

import io.release.monorepo.MonorepoDefaultSettings
import munit.FunSuite

class MonorepoDefaultSettingsSpec extends FunSuite {

  test("pluginDefaultSettings include representative monorepo defaults") {
    val labels = MonorepoDefaultSettings.pluginDefaultSettings.map(_.key.key.label).toSet

    assert(labels.contains(MonorepoReleaseIO.releaseIOMonorepoBehaviorCrossBuild.key.label))
    assert(labels.contains(MonorepoReleaseIO.releaseIOMonorepoPolicyEnablePublish.key.label))
    assert(labels.contains(MonorepoReleaseIO.releaseIOMonorepoSelectionProjects.key.label))
    assert(labels.contains(MonorepoReleaseIO.releaseIOMonorepoVersioningFile.key.label))
    assert(labels.contains(MonorepoReleaseIO.releaseIOMonorepoPublishChecks.key.label))
  }

  test("pluginDefaultSettings include each lifecycle-derived default exactly once") {
    val counts =
      MonorepoDefaultSettings.pluginDefaultSettings
        .groupBy(_.key.key.label)
        .map { case (label, settings) => label -> settings.size }
        .toMap

    MonorepoLifecycle.configDefaultSettings.map(_.key.key.label).distinct.foreach { label =>
      assertEquals(counts.getOrElse(label, 0), 1, label)
    }
  }
}
