package io.release.monorepo

import cats.effect.IO
import io.release.TestRepoFiles
import munit.CatsEffectSuite
import sbt.SettingKey

class MonorepoReleaseIOGroupedKeysSpec
    extends CatsEffectSuite
    with MonorepoReleasePluginSpecSupport {

  private def groupedKeyRef[A](key: SettingKey[A]): (String, AnyRef) =
    key.key.label -> key.asInstanceOf[AnyRef]

  private val groupedPublicKeys = Vector(
    groupedKeyRef(MonorepoReleaseIO.releaseIOMonorepoSelectionProjects),
    groupedKeyRef(MonorepoReleaseIO.releaseIOMonorepoBehaviorCrossBuild),
    groupedKeyRef(MonorepoReleaseIO.releaseIOMonorepoBehaviorSkipTests),
    groupedKeyRef(MonorepoReleaseIO.releaseIOMonorepoBehaviorSkipPublish),
    groupedKeyRef(MonorepoReleaseIO.releaseIOMonorepoBehaviorInteractive),
    groupedKeyRef(MonorepoReleaseIO.releaseIOMonorepoPolicyEnableSnapshotDependenciesCheck),
    groupedKeyRef(MonorepoReleaseIO.releaseIOMonorepoPolicyEnableRunClean),
    groupedKeyRef(MonorepoReleaseIO.releaseIOMonorepoPolicyEnableRunTests),
    groupedKeyRef(MonorepoReleaseIO.releaseIOMonorepoPolicyEnableTagging),
    groupedKeyRef(MonorepoReleaseIO.releaseIOMonorepoPolicyEnablePublish),
    groupedKeyRef(MonorepoReleaseIO.releaseIOMonorepoPolicyEnablePush),
    groupedKeyRef(MonorepoReleaseIO.releaseIOMonorepoHooksAfterCleanCheck),
    groupedKeyRef(MonorepoReleaseIO.releaseIOMonorepoHooksBeforeSelection),
    groupedKeyRef(MonorepoReleaseIO.releaseIOMonorepoHooksAfterSelection),
    groupedKeyRef(MonorepoReleaseIO.releaseIOMonorepoHooksBeforeVersionResolution),
    groupedKeyRef(MonorepoReleaseIO.releaseIOMonorepoHooksAfterVersionResolution),
    groupedKeyRef(MonorepoReleaseIO.releaseIOMonorepoHooksBeforeReleaseVersionWrite),
    groupedKeyRef(MonorepoReleaseIO.releaseIOMonorepoHooksAfterReleaseVersionWrite),
    groupedKeyRef(MonorepoReleaseIO.releaseIOMonorepoHooksBeforeReleaseCommit),
    groupedKeyRef(MonorepoReleaseIO.releaseIOMonorepoHooksAfterReleaseCommit),
    groupedKeyRef(MonorepoReleaseIO.releaseIOMonorepoHooksBeforeTag),
    groupedKeyRef(MonorepoReleaseIO.releaseIOMonorepoHooksAfterTag),
    groupedKeyRef(MonorepoReleaseIO.releaseIOMonorepoHooksBeforePublish),
    groupedKeyRef(MonorepoReleaseIO.releaseIOMonorepoHooksAfterPublish),
    groupedKeyRef(MonorepoReleaseIO.releaseIOMonorepoHooksBeforeNextVersionWrite),
    groupedKeyRef(MonorepoReleaseIO.releaseIOMonorepoHooksAfterNextVersionWrite),
    groupedKeyRef(MonorepoReleaseIO.releaseIOMonorepoHooksBeforeNextCommit),
    groupedKeyRef(MonorepoReleaseIO.releaseIOMonorepoHooksAfterNextCommit),
    groupedKeyRef(MonorepoReleaseIO.releaseIOMonorepoHooksBeforePush),
    groupedKeyRef(MonorepoReleaseIO.releaseIOMonorepoHooksAfterPush),
    groupedKeyRef(MonorepoReleaseIO.releaseIOMonorepoVersioningFile),
    groupedKeyRef(MonorepoReleaseIO.releaseIOMonorepoVersioningReadVersion),
    groupedKeyRef(MonorepoReleaseIO.releaseIOMonorepoVersioningFileContents),
    groupedKeyRef(MonorepoReleaseIO.releaseIOMonorepoDetectionEnabled),
    groupedKeyRef(MonorepoReleaseIO.releaseIOMonorepoDetectionIncludeDownstream),
    groupedKeyRef(MonorepoReleaseIO.releaseIOMonorepoDetectionChangeDetector),
    groupedKeyRef(MonorepoReleaseIO.releaseIOMonorepoDetectionExcludes),
    groupedKeyRef(MonorepoReleaseIO.releaseIOMonorepoDetectionSharedPaths),
    groupedKeyRef(MonorepoReleaseIO.releaseIOMonorepoVcsTagName),
    groupedKeyRef(MonorepoReleaseIO.releaseIOMonorepoVcsTagComment),
    groupedKeyRef(MonorepoReleaseIO.releaseIOMonorepoVcsReleaseCommitMessage),
    groupedKeyRef(MonorepoReleaseIO.releaseIOMonorepoVcsNextCommitMessage),
    groupedKeyRef(MonorepoReleaseIO.releaseIOMonorepoPublishChecks)
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

  private lazy val monorepoReleaseIOSource =
    TestRepoFiles.readString(
      "modules/monorepo/src/main/scala/io/release/monorepo/MonorepoReleaseIO.scala"
    )

  test("grouped monorepo keys cover the full catalog and expose exact catalog-backed instances") {
    val groupedByLabel = groupedPublicKeys.toMap

    assertEquals(groupedPublicKeys.map(_._1), MonorepoPublicKeyCatalog.publicEntries.map(_.label))
    assertEquals(groupedByLabel.keySet, MonorepoPublicKeyCatalog.publicEntries.map(_.label).toSet)

    MonorepoPublicKeyCatalog.publicEntries.foreach { entry =>
      assert(groupedByLabel(entry.label) eq entry.keyRef, entry.label)
    }
  }

  test("grouped monorepo settings resolve expected defaults") {
    stateResource("monorepo-grouped-keys", HookFriendlyPlugin).use { loaded =>
      IO {
        val extracted = io.release.TestkitSbtCompat.extract(loaded.state)

        assertEquals(extracted.get(MonorepoReleaseIO.releaseIOMonorepoBehaviorCrossBuild), false)
        assertEquals(extracted.get(MonorepoReleaseIO.releaseIOMonorepoBehaviorSkipTests), false)
        assertEquals(extracted.get(MonorepoReleaseIO.releaseIOMonorepoBehaviorSkipPublish), false)
        assertEquals(extracted.get(MonorepoReleaseIO.releaseIOMonorepoPolicyEnablePublish), true)
        assertEquals(
          extracted.get(MonorepoReleaseIO.releaseIOMonorepoHooksAfterCleanCheck),
          Seq.empty
        )
        assertEquals(extracted.get(MonorepoReleaseIO.releaseIOMonorepoPublishChecks), true)
      }
    }
  }

  test("MonorepoReleaseIO source no longer defines deprecated alias vals") {
    assert(!monorepoReleaseIOSource.contains("@deprecated("))
    removedAliases.foreach { name =>
      assert(
        !monorepoReleaseIOSource.contains(s"val $name"),
        s"Expected $name to be removed from MonorepoReleaseIO.scala"
      )
    }
  }
}
