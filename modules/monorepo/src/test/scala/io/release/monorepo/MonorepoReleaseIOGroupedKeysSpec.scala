package io.release.monorepo

import cats.effect.IO
import io.release.TestRepoFiles
import munit.CatsEffectSuite
import sbt.SettingKey

class MonorepoReleaseIOGroupedKeysSpec
    extends CatsEffectSuite
    with MonorepoReleasePluginSpecSupport {

  private def keyLabel[A](key: SettingKey[A]): String = key.key.label

  private val expectedLabels = Set(
    "releaseIOMonorepoSelectionProjects",
    "releaseIOMonorepoBehaviorCrossBuild",
    "releaseIOMonorepoBehaviorSkipTests",
    "releaseIOMonorepoBehaviorSkipPublish",
    "releaseIOMonorepoBehaviorInteractive",
    "releaseIOMonorepoPolicyEnableSnapshotDependenciesCheck",
    "releaseIOMonorepoPolicyEnableRunClean",
    "releaseIOMonorepoPolicyEnableRunTests",
    "releaseIOMonorepoPolicyEnableTagging",
    "releaseIOMonorepoPolicyEnablePublish",
    "releaseIOMonorepoPolicyEnablePush",
    "releaseIOMonorepoHooksAfterCleanCheck",
    "releaseIOMonorepoHooksBeforeSelection",
    "releaseIOMonorepoHooksAfterSelection",
    "releaseIOMonorepoHooksBeforeVersionResolution",
    "releaseIOMonorepoHooksAfterVersionResolution",
    "releaseIOMonorepoHooksBeforeReleaseVersionWrite",
    "releaseIOMonorepoHooksAfterReleaseVersionWrite",
    "releaseIOMonorepoHooksBeforeReleaseCommit",
    "releaseIOMonorepoHooksAfterReleaseCommit",
    "releaseIOMonorepoHooksBeforeTag",
    "releaseIOMonorepoHooksAfterTag",
    "releaseIOMonorepoHooksBeforePublish",
    "releaseIOMonorepoHooksAfterPublish",
    "releaseIOMonorepoHooksBeforeNextVersionWrite",
    "releaseIOMonorepoHooksAfterNextVersionWrite",
    "releaseIOMonorepoHooksBeforeNextCommit",
    "releaseIOMonorepoHooksAfterNextCommit",
    "releaseIOMonorepoHooksBeforePush",
    "releaseIOMonorepoHooksAfterPush",
    "releaseIOMonorepoVersioningFile",
    "releaseIOMonorepoVersioningReadVersion",
    "releaseIOMonorepoVersioningFileContents",
    "releaseIOMonorepoDetectionEnabled",
    "releaseIOMonorepoDetectionIncludeDownstream",
    "releaseIOMonorepoDetectionChangeDetector",
    "releaseIOMonorepoDetectionExcludes",
    "releaseIOMonorepoDetectionSharedPaths",
    "releaseIOMonorepoVcsTagName",
    "releaseIOMonorepoVcsTagComment",
    "releaseIOMonorepoVcsReleaseCommitMessage",
    "releaseIOMonorepoVcsNextCommitMessage",
    "releaseIOMonorepoPublishChecks"
  )

  private val compileTimeLabels = Set(
    keyLabel(MonorepoReleasePlugin.autoImport.releaseIOMonorepoSelectionProjects),
    keyLabel(MonorepoReleasePlugin.autoImport.releaseIOMonorepoBehaviorCrossBuild),
    keyLabel(MonorepoReleasePlugin.autoImport.releaseIOMonorepoBehaviorSkipTests),
    keyLabel(MonorepoReleasePlugin.autoImport.releaseIOMonorepoBehaviorSkipPublish),
    keyLabel(MonorepoReleasePlugin.autoImport.releaseIOMonorepoBehaviorInteractive),
    keyLabel(
      MonorepoReleasePlugin.autoImport.releaseIOMonorepoPolicyEnableSnapshotDependenciesCheck
    ),
    keyLabel(MonorepoReleasePlugin.autoImport.releaseIOMonorepoPolicyEnableRunClean),
    keyLabel(MonorepoReleasePlugin.autoImport.releaseIOMonorepoPolicyEnableRunTests),
    keyLabel(MonorepoReleasePlugin.autoImport.releaseIOMonorepoPolicyEnableTagging),
    keyLabel(MonorepoReleasePlugin.autoImport.releaseIOMonorepoPolicyEnablePublish),
    keyLabel(MonorepoReleasePlugin.autoImport.releaseIOMonorepoPolicyEnablePush),
    keyLabel(MonorepoReleasePlugin.autoImport.releaseIOMonorepoHooksAfterCleanCheck),
    keyLabel(MonorepoReleasePlugin.autoImport.releaseIOMonorepoHooksBeforeSelection),
    keyLabel(MonorepoReleasePlugin.autoImport.releaseIOMonorepoHooksAfterSelection),
    keyLabel(MonorepoReleasePlugin.autoImport.releaseIOMonorepoHooksBeforeVersionResolution),
    keyLabel(MonorepoReleasePlugin.autoImport.releaseIOMonorepoHooksAfterVersionResolution),
    keyLabel(MonorepoReleasePlugin.autoImport.releaseIOMonorepoHooksBeforeReleaseVersionWrite),
    keyLabel(MonorepoReleasePlugin.autoImport.releaseIOMonorepoHooksAfterReleaseVersionWrite),
    keyLabel(MonorepoReleasePlugin.autoImport.releaseIOMonorepoHooksBeforeReleaseCommit),
    keyLabel(MonorepoReleasePlugin.autoImport.releaseIOMonorepoHooksAfterReleaseCommit),
    keyLabel(MonorepoReleasePlugin.autoImport.releaseIOMonorepoHooksBeforeTag),
    keyLabel(MonorepoReleasePlugin.autoImport.releaseIOMonorepoHooksAfterTag),
    keyLabel(MonorepoReleasePlugin.autoImport.releaseIOMonorepoHooksBeforePublish),
    keyLabel(MonorepoReleasePlugin.autoImport.releaseIOMonorepoHooksAfterPublish),
    keyLabel(MonorepoReleasePlugin.autoImport.releaseIOMonorepoHooksBeforeNextVersionWrite),
    keyLabel(MonorepoReleasePlugin.autoImport.releaseIOMonorepoHooksAfterNextVersionWrite),
    keyLabel(MonorepoReleasePlugin.autoImport.releaseIOMonorepoHooksBeforeNextCommit),
    keyLabel(MonorepoReleasePlugin.autoImport.releaseIOMonorepoHooksAfterNextCommit),
    keyLabel(MonorepoReleasePlugin.autoImport.releaseIOMonorepoHooksBeforePush),
    keyLabel(MonorepoReleasePlugin.autoImport.releaseIOMonorepoHooksAfterPush),
    keyLabel(MonorepoReleasePlugin.autoImport.releaseIOMonorepoVersioningFile),
    keyLabel(MonorepoReleasePlugin.autoImport.releaseIOMonorepoVersioningReadVersion),
    keyLabel(MonorepoReleasePlugin.autoImport.releaseIOMonorepoVersioningFileContents),
    keyLabel(MonorepoReleasePlugin.autoImport.releaseIOMonorepoDetectionEnabled),
    keyLabel(MonorepoReleasePlugin.autoImport.releaseIOMonorepoDetectionIncludeDownstream),
    keyLabel(MonorepoReleasePlugin.autoImport.releaseIOMonorepoDetectionChangeDetector),
    keyLabel(MonorepoReleasePlugin.autoImport.releaseIOMonorepoDetectionExcludes),
    keyLabel(MonorepoReleasePlugin.autoImport.releaseIOMonorepoDetectionSharedPaths),
    keyLabel(MonorepoReleasePlugin.autoImport.releaseIOMonorepoVcsTagName),
    keyLabel(MonorepoReleasePlugin.autoImport.releaseIOMonorepoVcsTagComment),
    keyLabel(MonorepoReleasePlugin.autoImport.releaseIOMonorepoVcsReleaseCommitMessage),
    keyLabel(MonorepoReleasePlugin.autoImport.releaseIOMonorepoVcsNextCommitMessage),
    keyLabel(MonorepoReleasePlugin.autoImport.releaseIOMonorepoPublishChecks)
  )

  private lazy val reflectiveLabels = {
    val autoImport      = MonorepoReleasePlugin.autoImport
    val autoImportClass = autoImport.getClass

    autoImportClass.getMethods.iterator
      .filter(method =>
        method.getDeclaringClass == autoImportClass &&
          method.getParameterCount == 0 &&
          classOf[SettingKey[?]].isAssignableFrom(method.getReturnType)
      )
      .map(method =>
        method.invoke(autoImport) match {
          case key: SettingKey[?] => keyLabel(key)
          case other              =>
            fail(
              s"Expected ${method.getName} to return SettingKey, got ${other.getClass.getName}"
            )
        }
      )
      .toSet
  }

  private val removedAliases = Seq(
    "releaseIOMonorepoProjects",
    "releaseIOMonorepoEnableSnapshotDependenciesCheck",
    "releaseIOMonorepoEnableRunClean",
    "releaseIOMonorepoEnableRunTests",
    "releaseIOMonorepoEnableTagging",
    "releaseIOMonorepoEnablePublish",
    "releaseIOMonorepoEnablePush",
    "releaseIOMonorepoAfterCleanCheckHooks",
    "releaseIOMonorepoBeforeSelectionHooks",
    "releaseIOMonorepoAfterSelectionHooks",
    "releaseIOMonorepoBeforeVersionResolutionHooks",
    "releaseIOMonorepoAfterVersionResolutionHooks",
    "releaseIOMonorepoBeforeReleaseVersionWriteHooks",
    "releaseIOMonorepoAfterReleaseVersionWriteHooks",
    "releaseIOMonorepoBeforeReleaseCommitHooks",
    "releaseIOMonorepoAfterReleaseCommitHooks",
    "releaseIOMonorepoBeforeTagHooks",
    "releaseIOMonorepoAfterTagHooks",
    "releaseIOMonorepoBeforePublishHooks",
    "releaseIOMonorepoAfterPublishHooks",
    "releaseIOMonorepoBeforeNextVersionWriteHooks",
    "releaseIOMonorepoAfterNextVersionWriteHooks",
    "releaseIOMonorepoBeforeNextCommitHooks",
    "releaseIOMonorepoAfterNextCommitHooks",
    "releaseIOMonorepoBeforePushHooks",
    "releaseIOMonorepoAfterPushHooks",
    "releaseIOMonorepoVersionFile",
    "releaseIOMonorepoReadVersion",
    "releaseIOMonorepoVersionFileContents",
    "releaseIOMonorepoTagName",
    "releaseIOMonorepoTagComment",
    "releaseIOMonorepoDetectChanges",
    "releaseIOMonorepoChangeDetector",
    "releaseIOMonorepoDetectChangesExcludes",
    "releaseIOMonorepoSharedPaths",
    "releaseIOMonorepoIncludeDownstream",
    "releaseIOMonorepoCrossBuild",
    "releaseIOMonorepoSkipTests",
    "releaseIOMonorepoSkipPublish",
    "releaseIOMonorepoInteractive",
    "releaseIOMonorepoPublishArtifactsChecks",
    "releaseIOMonorepoCommitMessage",
    "releaseIOMonorepoNextCommitMessage"
  )

  private val autoImportNonProjectMembers = Seq(
    "ResolvedMonorepoTagSettings",
    "resolveTagSettings",
    "monorepoDefaultSettings",
    "MonorepoVersionFileResolver"
  )

  private lazy val monorepoPluginSource =
    TestRepoFiles.readString(
      "modules/monorepo/src/main/scala/io/release/monorepo/MonorepoReleasePlugin.scala"
    )

  test("MonorepoReleasePlugin.autoImport exposes the full set of 43 expected public keys") {
    assertEquals(compileTimeLabels, expectedLabels)
    assertEquals(reflectiveLabels, expectedLabels)
    assertEquals(reflectiveLabels.size, 43)
  }

  test(
    "grouped monorepo settings resolve expected defaults from MonorepoReleasePlugin.autoImport"
  ) {
    stateResource("monorepo-grouped-keys", HookFriendlyPlugin).use { loaded =>
      IO {
        val extracted = io.release.TestkitSbtCompat.extract(loaded.state)

        assertEquals(
          extracted.get(MonorepoReleasePlugin.autoImport.releaseIOMonorepoBehaviorCrossBuild),
          false
        )
        assertEquals(
          extracted.get(MonorepoReleasePlugin.autoImport.releaseIOMonorepoBehaviorSkipTests),
          false
        )
        assertEquals(
          extracted.get(MonorepoReleasePlugin.autoImport.releaseIOMonorepoBehaviorSkipPublish),
          false
        )
        assertEquals(
          extracted.get(MonorepoReleasePlugin.autoImport.releaseIOMonorepoPolicyEnablePublish),
          true
        )
        assertEquals(
          extracted.get(MonorepoReleasePlugin.autoImport.releaseIOMonorepoHooksAfterCleanCheck),
          Seq.empty
        )
        assertEquals(
          extracted.get(MonorepoReleasePlugin.autoImport.releaseIOMonorepoPublishChecks),
          true
        )
      }
    }
  }

  test("MonorepoReleasePlugin source keeps autoImport limited to grouped public project keys") {
    assert(monorepoPluginSource.contains("object MonorepoReleasePluginAutoImport"))
    removedAliases.foreach { name =>
      assert(
        !s"(?m)^\\s+(?:lazy\\s+)?val ${java.util.regex.Pattern.quote(name)}\\b".r
          .findFirstIn(monorepoPluginSource)
          .isDefined,
        s"Expected $name to stay out of MonorepoReleasePlugin.scala"
      )
    }
    autoImportNonProjectMembers.foreach { name =>
      assert(
        !monorepoPluginSource.contains(name),
        s"Expected $name to stay out of MonorepoReleasePlugin.scala autoImport keys"
      )
    }
    assert(!monorepoPluginSource.contains("extends AutoPlugin with MonorepoReleaseIO"))
    assert(!monorepoPluginSource.contains("[[MonorepoReleaseIO]]"))
  }
}
