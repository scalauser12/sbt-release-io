package io.release.monorepo

import io.release.monorepo.internal.*

import cats.effect.IO
import io.release.TestRepoFiles
import munit.CatsEffectSuite
import sbt.SettingKey

import java.io.File

import scala.annotation.nowarn

class MonorepoReleaseIOGroupedKeysSpec
    extends CatsEffectSuite
    with MonorepoReleasePluginSpecSupport {

  private def keyLabel[A](key: SettingKey[A]): String = key.key.label

  @nowarn("cat=deprecation")
  private object CompatibilityMixin extends MonorepoReleaseIO

  @nowarn("cat=deprecation")
  private val compatibilityMixinResolver: CompatibilityMixin.MonorepoVersionFileResolver =
    (_, _) => new File(".")

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

  private val actualLabels = Set(
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

  private lazy val monorepoReleaseIOSource =
    TestRepoFiles.readString(
      "modules/monorepo/src/main/scala/io/release/monorepo/MonorepoReleaseIO.scala"
    )

  @nowarn("cat=deprecation")
  private def compatibilityLabels: Set[String] = Set(
    keyLabel(MonorepoReleaseIO.releaseIOMonorepoSelectionProjects),
    keyLabel(MonorepoReleaseIO.releaseIOMonorepoBehaviorCrossBuild),
    keyLabel(MonorepoReleaseIO.releaseIOMonorepoBehaviorSkipTests),
    keyLabel(MonorepoReleaseIO.releaseIOMonorepoBehaviorSkipPublish),
    keyLabel(MonorepoReleaseIO.releaseIOMonorepoBehaviorInteractive),
    keyLabel(MonorepoReleaseIO.releaseIOMonorepoPolicyEnableSnapshotDependenciesCheck),
    keyLabel(MonorepoReleaseIO.releaseIOMonorepoPolicyEnableRunClean),
    keyLabel(MonorepoReleaseIO.releaseIOMonorepoPolicyEnableRunTests),
    keyLabel(MonorepoReleaseIO.releaseIOMonorepoPolicyEnableTagging),
    keyLabel(MonorepoReleaseIO.releaseIOMonorepoPolicyEnablePublish),
    keyLabel(MonorepoReleaseIO.releaseIOMonorepoPolicyEnablePush),
    keyLabel(MonorepoReleaseIO.releaseIOMonorepoHooksAfterCleanCheck),
    keyLabel(MonorepoReleaseIO.releaseIOMonorepoHooksBeforeSelection),
    keyLabel(MonorepoReleaseIO.releaseIOMonorepoHooksAfterSelection),
    keyLabel(MonorepoReleaseIO.releaseIOMonorepoHooksBeforeVersionResolution),
    keyLabel(MonorepoReleaseIO.releaseIOMonorepoHooksAfterVersionResolution),
    keyLabel(MonorepoReleaseIO.releaseIOMonorepoHooksBeforeReleaseVersionWrite),
    keyLabel(MonorepoReleaseIO.releaseIOMonorepoHooksAfterReleaseVersionWrite),
    keyLabel(MonorepoReleaseIO.releaseIOMonorepoHooksBeforeReleaseCommit),
    keyLabel(MonorepoReleaseIO.releaseIOMonorepoHooksAfterReleaseCommit),
    keyLabel(MonorepoReleaseIO.releaseIOMonorepoHooksBeforeTag),
    keyLabel(MonorepoReleaseIO.releaseIOMonorepoHooksAfterTag),
    keyLabel(MonorepoReleaseIO.releaseIOMonorepoHooksBeforePublish),
    keyLabel(MonorepoReleaseIO.releaseIOMonorepoHooksAfterPublish),
    keyLabel(MonorepoReleaseIO.releaseIOMonorepoHooksBeforeNextVersionWrite),
    keyLabel(MonorepoReleaseIO.releaseIOMonorepoHooksAfterNextVersionWrite),
    keyLabel(MonorepoReleaseIO.releaseIOMonorepoHooksBeforeNextCommit),
    keyLabel(MonorepoReleaseIO.releaseIOMonorepoHooksAfterNextCommit),
    keyLabel(MonorepoReleaseIO.releaseIOMonorepoHooksBeforePush),
    keyLabel(MonorepoReleaseIO.releaseIOMonorepoHooksAfterPush),
    keyLabel(MonorepoReleaseIO.releaseIOMonorepoVersioningFile),
    keyLabel(MonorepoReleaseIO.releaseIOMonorepoVersioningReadVersion),
    keyLabel(MonorepoReleaseIO.releaseIOMonorepoVersioningFileContents),
    keyLabel(MonorepoReleaseIO.releaseIOMonorepoDetectionEnabled),
    keyLabel(MonorepoReleaseIO.releaseIOMonorepoDetectionIncludeDownstream),
    keyLabel(MonorepoReleaseIO.releaseIOMonorepoDetectionChangeDetector),
    keyLabel(MonorepoReleaseIO.releaseIOMonorepoDetectionExcludes),
    keyLabel(MonorepoReleaseIO.releaseIOMonorepoDetectionSharedPaths),
    keyLabel(MonorepoReleaseIO.releaseIOMonorepoVcsTagName),
    keyLabel(MonorepoReleaseIO.releaseIOMonorepoVcsTagComment),
    keyLabel(MonorepoReleaseIO.releaseIOMonorepoVcsReleaseCommitMessage),
    keyLabel(MonorepoReleaseIO.releaseIOMonorepoVcsNextCommitMessage),
    keyLabel(MonorepoReleaseIO.releaseIOMonorepoPublishChecks)
  )

  @nowarn("cat=deprecation")
  private def compatibilityRefs: Seq[(AnyRef, AnyRef)] = Seq(
    MonorepoReleaseIO.releaseIOMonorepoSelectionProjects                     -> MonorepoReleasePlugin.autoImport.releaseIOMonorepoSelectionProjects,
    MonorepoReleaseIO.releaseIOMonorepoBehaviorCrossBuild                    -> MonorepoReleasePlugin.autoImport.releaseIOMonorepoBehaviorCrossBuild,
    MonorepoReleaseIO.releaseIOMonorepoBehaviorSkipTests                     -> MonorepoReleasePlugin.autoImport.releaseIOMonorepoBehaviorSkipTests,
    MonorepoReleaseIO.releaseIOMonorepoBehaviorSkipPublish                   -> MonorepoReleasePlugin.autoImport.releaseIOMonorepoBehaviorSkipPublish,
    MonorepoReleaseIO.releaseIOMonorepoBehaviorInteractive                   -> MonorepoReleasePlugin.autoImport.releaseIOMonorepoBehaviorInteractive,
    MonorepoReleaseIO.releaseIOMonorepoPolicyEnableSnapshotDependenciesCheck -> MonorepoReleasePlugin.autoImport.releaseIOMonorepoPolicyEnableSnapshotDependenciesCheck,
    MonorepoReleaseIO.releaseIOMonorepoPolicyEnableRunClean                  -> MonorepoReleasePlugin.autoImport.releaseIOMonorepoPolicyEnableRunClean,
    MonorepoReleaseIO.releaseIOMonorepoPolicyEnableRunTests                  -> MonorepoReleasePlugin.autoImport.releaseIOMonorepoPolicyEnableRunTests,
    MonorepoReleaseIO.releaseIOMonorepoPolicyEnableTagging                   -> MonorepoReleasePlugin.autoImport.releaseIOMonorepoPolicyEnableTagging,
    MonorepoReleaseIO.releaseIOMonorepoPolicyEnablePublish                   -> MonorepoReleasePlugin.autoImport.releaseIOMonorepoPolicyEnablePublish,
    MonorepoReleaseIO.releaseIOMonorepoPolicyEnablePush                      -> MonorepoReleasePlugin.autoImport.releaseIOMonorepoPolicyEnablePush,
    MonorepoReleaseIO.releaseIOMonorepoHooksAfterCleanCheck                  -> MonorepoReleasePlugin.autoImport.releaseIOMonorepoHooksAfterCleanCheck,
    MonorepoReleaseIO.releaseIOMonorepoHooksBeforeSelection                  -> MonorepoReleasePlugin.autoImport.releaseIOMonorepoHooksBeforeSelection,
    MonorepoReleaseIO.releaseIOMonorepoHooksAfterSelection                   -> MonorepoReleasePlugin.autoImport.releaseIOMonorepoHooksAfterSelection,
    MonorepoReleaseIO.releaseIOMonorepoHooksBeforeVersionResolution          -> MonorepoReleasePlugin.autoImport.releaseIOMonorepoHooksBeforeVersionResolution,
    MonorepoReleaseIO.releaseIOMonorepoHooksAfterVersionResolution           -> MonorepoReleasePlugin.autoImport.releaseIOMonorepoHooksAfterVersionResolution,
    MonorepoReleaseIO.releaseIOMonorepoHooksBeforeReleaseVersionWrite        -> MonorepoReleasePlugin.autoImport.releaseIOMonorepoHooksBeforeReleaseVersionWrite,
    MonorepoReleaseIO.releaseIOMonorepoHooksAfterReleaseVersionWrite         -> MonorepoReleasePlugin.autoImport.releaseIOMonorepoHooksAfterReleaseVersionWrite,
    MonorepoReleaseIO.releaseIOMonorepoHooksBeforeReleaseCommit              -> MonorepoReleasePlugin.autoImport.releaseIOMonorepoHooksBeforeReleaseCommit,
    MonorepoReleaseIO.releaseIOMonorepoHooksAfterReleaseCommit               -> MonorepoReleasePlugin.autoImport.releaseIOMonorepoHooksAfterReleaseCommit,
    MonorepoReleaseIO.releaseIOMonorepoHooksBeforeTag                        -> MonorepoReleasePlugin.autoImport.releaseIOMonorepoHooksBeforeTag,
    MonorepoReleaseIO.releaseIOMonorepoHooksAfterTag                         -> MonorepoReleasePlugin.autoImport.releaseIOMonorepoHooksAfterTag,
    MonorepoReleaseIO.releaseIOMonorepoHooksBeforePublish                    -> MonorepoReleasePlugin.autoImport.releaseIOMonorepoHooksBeforePublish,
    MonorepoReleaseIO.releaseIOMonorepoHooksAfterPublish                     -> MonorepoReleasePlugin.autoImport.releaseIOMonorepoHooksAfterPublish,
    MonorepoReleaseIO.releaseIOMonorepoHooksBeforeNextVersionWrite           -> MonorepoReleasePlugin.autoImport.releaseIOMonorepoHooksBeforeNextVersionWrite,
    MonorepoReleaseIO.releaseIOMonorepoHooksAfterNextVersionWrite            -> MonorepoReleasePlugin.autoImport.releaseIOMonorepoHooksAfterNextVersionWrite,
    MonorepoReleaseIO.releaseIOMonorepoHooksBeforeNextCommit                 -> MonorepoReleasePlugin.autoImport.releaseIOMonorepoHooksBeforeNextCommit,
    MonorepoReleaseIO.releaseIOMonorepoHooksAfterNextCommit                  -> MonorepoReleasePlugin.autoImport.releaseIOMonorepoHooksAfterNextCommit,
    MonorepoReleaseIO.releaseIOMonorepoHooksBeforePush                       -> MonorepoReleasePlugin.autoImport.releaseIOMonorepoHooksBeforePush,
    MonorepoReleaseIO.releaseIOMonorepoHooksAfterPush                        -> MonorepoReleasePlugin.autoImport.releaseIOMonorepoHooksAfterPush,
    MonorepoReleaseIO.releaseIOMonorepoVersioningFile                        -> MonorepoReleasePlugin.autoImport.releaseIOMonorepoVersioningFile,
    MonorepoReleaseIO.releaseIOMonorepoVersioningReadVersion                 -> MonorepoReleasePlugin.autoImport.releaseIOMonorepoVersioningReadVersion,
    MonorepoReleaseIO.releaseIOMonorepoVersioningFileContents                -> MonorepoReleasePlugin.autoImport.releaseIOMonorepoVersioningFileContents,
    MonorepoReleaseIO.releaseIOMonorepoDetectionEnabled                      -> MonorepoReleasePlugin.autoImport.releaseIOMonorepoDetectionEnabled,
    MonorepoReleaseIO.releaseIOMonorepoDetectionIncludeDownstream            -> MonorepoReleasePlugin.autoImport.releaseIOMonorepoDetectionIncludeDownstream,
    MonorepoReleaseIO.releaseIOMonorepoDetectionChangeDetector               -> MonorepoReleasePlugin.autoImport.releaseIOMonorepoDetectionChangeDetector,
    MonorepoReleaseIO.releaseIOMonorepoDetectionExcludes                     -> MonorepoReleasePlugin.autoImport.releaseIOMonorepoDetectionExcludes,
    MonorepoReleaseIO.releaseIOMonorepoDetectionSharedPaths                  -> MonorepoReleasePlugin.autoImport.releaseIOMonorepoDetectionSharedPaths,
    MonorepoReleaseIO.releaseIOMonorepoVcsTagName                            -> MonorepoReleasePlugin.autoImport.releaseIOMonorepoVcsTagName,
    MonorepoReleaseIO.releaseIOMonorepoVcsTagComment                         -> MonorepoReleasePlugin.autoImport.releaseIOMonorepoVcsTagComment,
    MonorepoReleaseIO.releaseIOMonorepoVcsReleaseCommitMessage               -> MonorepoReleasePlugin.autoImport.releaseIOMonorepoVcsReleaseCommitMessage,
    MonorepoReleaseIO.releaseIOMonorepoVcsNextCommitMessage                  -> MonorepoReleasePlugin.autoImport.releaseIOMonorepoVcsNextCommitMessage,
    MonorepoReleaseIO.releaseIOMonorepoPublishChecks                         -> MonorepoReleasePlugin.autoImport.releaseIOMonorepoPublishChecks
  )

  @nowarn("cat=deprecation")
  private def compatibilityDefaultSettings: Seq[sbt.Setting[?]] =
    MonorepoReleaseIO.monorepoDefaultSettings

  test("MonorepoReleasePlugin.autoImport exposes the full set of 43 expected public keys") {
    assertEquals(actualLabels, expectedLabels)
    assertEquals(actualLabels.size, 43)
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

  test("MonorepoReleasePlugin source keeps autoImport limited to public project keys") {
    assert(monorepoPluginSource.contains("object MonorepoReleasePluginAutoImport"))
    autoImportNonProjectMembers.foreach { name =>
      assert(
        !monorepoPluginSource.contains(name),
        s"Expected $name to stay out of MonorepoReleasePlugin.scala autoImport keys"
      )
    }
  }

  test("MonorepoReleaseIO compatibility namespace forwards the canonical public keys") {
    assertEquals(compatibilityLabels, expectedLabels)
    compatibilityRefs.foreach { case (legacy, canonical) =>
      assert(legacy eq canonical)
    }
    assert(compatibilityDefaultSettings eq MonorepoDefaultSettings.pluginDefaultSettings)
  }

  test("MonorepoReleaseIO remains usable as a deprecated mixin") {
    assertEquals(
      keyLabel(CompatibilityMixin.releaseIOMonorepoBehaviorCrossBuild),
      "releaseIOMonorepoBehaviorCrossBuild"
    )
    assert(
      CompatibilityMixin.releaseIOMonorepoHooksAfterSelection eq MonorepoReleasePlugin.autoImport.releaseIOMonorepoHooksAfterSelection
    )
    assert(
      CompatibilityMixin.monorepoDefaultSettings eq MonorepoDefaultSettings.pluginDefaultSettings
    )
    assert(compatibilityMixinResolver != null)
  }

  test(
    "MonorepoReleaseIO source is a deprecated compatibility mixin namespace without removed aliases"
  ) {
    assert(
      monorepoReleaseIOSource.contains(
        """@deprecated("Use MonorepoReleasePlugin.autoImport instead."""
      )
    )
    assert(monorepoReleaseIOSource.contains("trait MonorepoReleaseIO"))
    assert(monorepoReleaseIOSource.contains("object MonorepoReleaseIO extends MonorepoReleaseIO"))
    removedAliases.foreach { name =>
      assert(
        !s"(?m)^\\s+(?:lazy\\s+)?val ${java.util.regex.Pattern.quote(name)}\\b".r
          .findFirstIn(monorepoReleaseIOSource)
          .isDefined,
        s"Expected $name to be removed from MonorepoReleaseIO.scala"
      )
    }
    assert(monorepoReleaseIOSource.contains("type MonorepoVersionFileResolver"))
    assert(monorepoReleaseIOSource.contains("val monorepoDefaultSettings"))
  }
}
