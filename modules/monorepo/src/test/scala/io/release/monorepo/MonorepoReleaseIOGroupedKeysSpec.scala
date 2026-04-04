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

  private val actualLabels = Set(
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

  test("MonorepoReleaseIO exposes the full set of 43 expected public keys") {
    assertEquals(actualLabels, expectedLabels)
    assertEquals(actualLabels.size, 43)
  }

  test("grouped monorepo settings resolve expected defaults") {
    stateResource("monorepo-grouped-keys", HookFriendlyPlugin).use { loaded =>
      IO {
        val extracted = io.release.TestkitSbtCompat.extract(loaded.state)

        assertEquals(
          extracted.get(MonorepoReleaseIO.releaseIOMonorepoBehaviorCrossBuild),
          false
        )
        assertEquals(
          extracted.get(MonorepoReleaseIO.releaseIOMonorepoBehaviorSkipTests),
          false
        )
        assertEquals(
          extracted.get(MonorepoReleaseIO.releaseIOMonorepoBehaviorSkipPublish),
          false
        )
        assertEquals(
          extracted.get(MonorepoReleaseIO.releaseIOMonorepoPolicyEnablePublish),
          true
        )
        assertEquals(
          extracted.get(MonorepoReleaseIO.releaseIOMonorepoHooksAfterCleanCheck),
          Seq.empty
        )
        assertEquals(
          extracted.get(MonorepoReleaseIO.releaseIOMonorepoPublishChecks),
          true
        )
      }
    }
  }

  test("MonorepoReleaseIO source no longer defines deprecated alias vals") {
    assert(!monorepoReleaseIOSource.contains("@deprecated("))
    removedAliases.foreach { name =>
      assert(
        !s"(?m)^\\s+(?:lazy\\s+)?val ${java.util.regex.Pattern.quote(name)}\\b".r
          .findFirstIn(monorepoReleaseIOSource)
          .isDefined,
        s"Expected $name to be removed from MonorepoReleaseIO.scala"
      )
    }
  }

  test("MonorepoReleaseIO source no longer defines private catalog-forwarding aliases") {
    assert(
      !monorepoReleaseIOSource.contains("MonorepoPublicKeyCatalog."),
      "Expected MonorepoReleaseIO.scala to stop forwarding catalog-backed public keys"
    )
    assert(
      !raw"private\[monorepo\]\s+lazy val _releaseIOMonorepo".r
        .findFirstIn(monorepoReleaseIOSource)
        .isDefined,
      "Expected MonorepoReleaseIO.scala to remove private _releaseIOMonorepo forwarding aliases"
    )
  }
}
