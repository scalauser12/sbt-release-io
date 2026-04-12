package io.release

import munit.FunSuite
import sbt.AutoPlugin
import sbt.SettingKey
import sbt.TaskKey

class ReleaseSharedPluginGroupedKeysSpec extends FunSuite {

  private object NoTriggerProbe extends AutoPlugin {
    override def trigger = noTrigger
  }

  private def keyLabel[A](key: SettingKey[A]): String = key.key.label
  private def keyLabel[A](key: TaskKey[A]): String    = key.key.label

  private def publicKeyLabels(autoImport: AnyRef): Set[String] = {
    val autoImportClass = autoImport.getClass

    autoImportClass.getMethods.iterator
      .filter(method =>
        method.getDeclaringClass == autoImportClass &&
          method.getParameterCount == 0 &&
          (
            classOf[SettingKey[?]].isAssignableFrom(method.getReturnType) ||
              classOf[TaskKey[?]].isAssignableFrom(method.getReturnType)
          )
      )
      .map(method =>
        method.invoke(autoImport) match {
          case key: SettingKey[?] => keyLabel(key)
          case key: TaskKey[?]    => keyLabel(key)
          case other              =>
            fail(
              s"Expected ${method.getName} to return SettingKey or TaskKey, got ${other.getClass.getName}"
            )
        }
      )
      .toSet
  }

  private val expectedLabels = Set(
    "releaseIODefaultsTagExistsAnswer",
    "releaseIODefaultsSnapshotDependenciesAnswer",
    "releaseIODefaultsRemoteCheckFailureAnswer",
    "releaseIODefaultsUpstreamBehindAnswer",
    "releaseIODefaultsPushAnswer",
    "releaseIOVersioningFile",
    "releaseIOVersioningBump",
    "releaseIOVersioningReleaseVersion",
    "releaseIOVersioningNextVersion",
    "releaseIOVcsSign",
    "releaseIOVcsSignOff",
    "releaseIOVcsIgnoreUntrackedFiles",
    "releaseIOVcsRemoteCheckTimeout",
    "releaseIOPublishAction",
    "releaseIODiagnosticsSnapshotDependencies"
  )

  private val compileTimeLabels = Set(
    keyLabel(ReleaseSharedPlugin.autoImport.releaseIODefaultsTagExistsAnswer),
    keyLabel(ReleaseSharedPlugin.autoImport.releaseIODefaultsSnapshotDependenciesAnswer),
    keyLabel(ReleaseSharedPlugin.autoImport.releaseIODefaultsRemoteCheckFailureAnswer),
    keyLabel(ReleaseSharedPlugin.autoImport.releaseIODefaultsUpstreamBehindAnswer),
    keyLabel(ReleaseSharedPlugin.autoImport.releaseIODefaultsPushAnswer),
    keyLabel(ReleaseSharedPlugin.autoImport.releaseIOVersioningFile),
    keyLabel(ReleaseSharedPlugin.autoImport.releaseIOVersioningBump),
    keyLabel(ReleaseSharedPlugin.autoImport.releaseIOVersioningReleaseVersion),
    keyLabel(ReleaseSharedPlugin.autoImport.releaseIOVersioningNextVersion),
    keyLabel(ReleaseSharedPlugin.autoImport.releaseIOVcsSign),
    keyLabel(ReleaseSharedPlugin.autoImport.releaseIOVcsSignOff),
    keyLabel(ReleaseSharedPlugin.autoImport.releaseIOVcsIgnoreUntrackedFiles),
    keyLabel(ReleaseSharedPlugin.autoImport.releaseIOVcsRemoteCheckTimeout),
    keyLabel(ReleaseSharedPlugin.autoImport.releaseIOPublishAction),
    keyLabel(ReleaseSharedPlugin.autoImport.releaseIODiagnosticsSnapshotDependencies)
  )

  private lazy val reflectiveLabels     = publicKeyLabels(ReleaseSharedPlugin.autoImport)
  private lazy val coreReflectiveLabels = publicKeyLabels(ReleasePluginIO.autoImport)

  private def assertSameSettingKey[A](
      shared: SettingKey[A],
      runtime: SettingKey[A]
  ): Unit =
    assert(shared eq runtime, s"Expected ${shared.key.label} to keep exact key identity")

  private def assertSameTaskKey[A](
      shared: TaskKey[A],
      runtime: TaskKey[A]
  ): Unit =
    assert(shared eq runtime, s"Expected ${shared.key.label} to keep exact key identity")

  test("ReleaseSharedPlugin.autoImport exposes the full set of 15 expected public keys") {
    assertEquals(compileTimeLabels, expectedLabels)
    assertEquals(reflectiveLabels, expectedLabels)
    assertEquals(reflectiveLabels.size, 15)
  }

  test("shared keys keep exact identity when exposed through ReleaseSharedPlugin.autoImport") {
    assertSameSettingKey(
      ReleaseSharedPlugin.autoImport.releaseIODefaultsTagExistsAnswer,
      ReleaseSharedKeys.releaseIODefaultsTagExistsAnswer
    )
    assertSameSettingKey(
      ReleaseSharedPlugin.autoImport.releaseIODefaultsSnapshotDependenciesAnswer,
      ReleaseSharedKeys.releaseIODefaultsSnapshotDependenciesAnswer
    )
    assertSameSettingKey(
      ReleaseSharedPlugin.autoImport.releaseIODefaultsRemoteCheckFailureAnswer,
      ReleaseSharedKeys.releaseIODefaultsRemoteCheckFailureAnswer
    )
    assertSameSettingKey(
      ReleaseSharedPlugin.autoImport.releaseIODefaultsUpstreamBehindAnswer,
      ReleaseSharedKeys.releaseIODefaultsUpstreamBehindAnswer
    )
    assertSameSettingKey(
      ReleaseSharedPlugin.autoImport.releaseIODefaultsPushAnswer,
      ReleaseSharedKeys.releaseIODefaultsPushAnswer
    )
    assertSameSettingKey(
      ReleaseSharedPlugin.autoImport.releaseIOVersioningFile,
      ReleaseSharedKeys.releaseIOVersioningFile
    )
    assertSameTaskKey(
      ReleaseSharedPlugin.autoImport.releaseIOVersioningBump,
      ReleaseSharedKeys.releaseIOVersioningBump
    )
    assertSameTaskKey(
      ReleaseSharedPlugin.autoImport.releaseIOVersioningReleaseVersion,
      ReleaseSharedKeys.releaseIOVersioningReleaseVersion
    )
    assertSameTaskKey(
      ReleaseSharedPlugin.autoImport.releaseIOVersioningNextVersion,
      ReleaseSharedKeys.releaseIOVersioningNextVersion
    )
    assertSameSettingKey(
      ReleaseSharedPlugin.autoImport.releaseIOVcsSign,
      ReleaseSharedKeys.releaseIOVcsSign
    )
    assertSameSettingKey(
      ReleaseSharedPlugin.autoImport.releaseIOVcsSignOff,
      ReleaseSharedKeys.releaseIOVcsSignOff
    )
    assertSameSettingKey(
      ReleaseSharedPlugin.autoImport.releaseIOVcsIgnoreUntrackedFiles,
      ReleaseSharedKeys.releaseIOVcsIgnoreUntrackedFiles
    )
    assertSameSettingKey(
      ReleaseSharedPlugin.autoImport.releaseIOVcsRemoteCheckTimeout,
      ReleaseSharedKeys.releaseIOVcsRemoteCheckTimeout
    )
    assertSameTaskKey(
      ReleaseSharedPlugin.autoImport.releaseIOPublishAction,
      ReleaseSharedKeys.releaseIOPublishAction
    )
    assertSameTaskKey(
      ReleaseSharedPlugin.autoImport.releaseIODiagnosticsSnapshotDependencies,
      ReleaseSharedKeys.releaseIODiagnosticsSnapshotDependencies
    )
  }

  test("ReleaseSharedPlugin stays opt-in to avoid ambiguous .sbt auto-imports") {
    assertEquals(ReleaseSharedPlugin.trigger, NoTriggerProbe.trigger)
  }

  test("shared build defaults do not set a build-wide releaseIOVersioningFile") {
    val buildDefaultLabels =
      ReleaseSharedDefaultSettingsSupport.buildDefaultSettings.map(_.key.key.label).toSet

    assert(!buildDefaultLabels.contains("releaseIOVersioningFile"))
  }

  test("shared keys remain reachable through ReleasePluginIO.autoImport compatibility aliases") {
    assertSameSettingKey(
      ReleasePluginIO.autoImport.releaseIODefaultsTagExistsAnswer,
      ReleaseSharedPlugin.autoImport.releaseIODefaultsTagExistsAnswer
    )
    assertSameSettingKey(
      ReleasePluginIO.autoImport.releaseIODefaultsSnapshotDependenciesAnswer,
      ReleaseSharedPlugin.autoImport.releaseIODefaultsSnapshotDependenciesAnswer
    )
    assertSameSettingKey(
      ReleasePluginIO.autoImport.releaseIODefaultsRemoteCheckFailureAnswer,
      ReleaseSharedPlugin.autoImport.releaseIODefaultsRemoteCheckFailureAnswer
    )
    assertSameSettingKey(
      ReleasePluginIO.autoImport.releaseIODefaultsUpstreamBehindAnswer,
      ReleaseSharedPlugin.autoImport.releaseIODefaultsUpstreamBehindAnswer
    )
    assertSameSettingKey(
      ReleasePluginIO.autoImport.releaseIODefaultsPushAnswer,
      ReleaseSharedPlugin.autoImport.releaseIODefaultsPushAnswer
    )
    assertSameSettingKey(
      ReleasePluginIO.autoImport.releaseIOVersioningFile,
      ReleaseSharedPlugin.autoImport.releaseIOVersioningFile
    )
    assertSameTaskKey(
      ReleasePluginIO.autoImport.releaseIOVersioningBump,
      ReleaseSharedPlugin.autoImport.releaseIOVersioningBump
    )
    assertSameTaskKey(
      ReleasePluginIO.autoImport.releaseIOVersioningReleaseVersion,
      ReleaseSharedPlugin.autoImport.releaseIOVersioningReleaseVersion
    )
    assertSameTaskKey(
      ReleasePluginIO.autoImport.releaseIOVersioningNextVersion,
      ReleaseSharedPlugin.autoImport.releaseIOVersioningNextVersion
    )
    assertSameSettingKey(
      ReleasePluginIO.autoImport.releaseIOVcsSign,
      ReleaseSharedPlugin.autoImport.releaseIOVcsSign
    )
    assertSameSettingKey(
      ReleasePluginIO.autoImport.releaseIOVcsSignOff,
      ReleaseSharedPlugin.autoImport.releaseIOVcsSignOff
    )
    assertSameSettingKey(
      ReleasePluginIO.autoImport.releaseIOVcsIgnoreUntrackedFiles,
      ReleaseSharedPlugin.autoImport.releaseIOVcsIgnoreUntrackedFiles
    )
    assertSameSettingKey(
      ReleasePluginIO.autoImport.releaseIOVcsRemoteCheckTimeout,
      ReleaseSharedPlugin.autoImport.releaseIOVcsRemoteCheckTimeout
    )
    assertSameTaskKey(
      ReleasePluginIO.autoImport.releaseIOPublishAction,
      ReleaseSharedPlugin.autoImport.releaseIOPublishAction
    )
    assertSameTaskKey(
      ReleasePluginIO.autoImport.releaseIODiagnosticsSnapshotDependencies,
      ReleaseSharedPlugin.autoImport.releaseIODiagnosticsSnapshotDependencies
    )
  }

  test("shared and core public grouped-key surfaces overlap exactly on the shared keys") {
    assertEquals(reflectiveLabels.intersect(coreReflectiveLabels), expectedLabels)
    assertEquals(reflectiveLabels.union(coreReflectiveLabels).size, 50)
  }
}
