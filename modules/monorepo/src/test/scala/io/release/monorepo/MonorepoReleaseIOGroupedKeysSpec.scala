package io.release.monorepo

import cats.effect.IO
import io.release.TestRepoFiles
import munit.CatsEffectSuite

class MonorepoReleaseIOGroupedKeysSpec
    extends CatsEffectSuite
    with MonorepoReleasePluginSpecSupport {

  private val canonicalLabels = Seq(
    "selection.projects"          -> (
      MonorepoReleaseIO.releaseIOMonorepoSelectionProjects.key.label,
      "releaseIOMonorepoSelectionProjects"
    ),
    "behavior.crossBuild"         -> (
      MonorepoReleaseIO.releaseIOMonorepoBehaviorCrossBuild.key.label,
      "releaseIOMonorepoBehaviorCrossBuild"
    ),
    "policy.publish"              -> (
      MonorepoReleaseIO.releaseIOMonorepoPolicyEnablePublish.key.label,
      "releaseIOMonorepoPolicyEnablePublish"
    ),
    "hooks.afterCleanCheck"       -> (
      MonorepoReleaseIO.releaseIOMonorepoHooksAfterCleanCheck.key.label,
      "releaseIOMonorepoHooksAfterCleanCheck"
    ),
    "versioning.file"             -> (
      MonorepoReleaseIO.releaseIOMonorepoVersioningFile.key.label,
      "releaseIOMonorepoVersioningFile"
    ),
    "vcs.tagName"                 -> (
      MonorepoReleaseIO.releaseIOMonorepoVcsTagName.key.label,
      "releaseIOMonorepoVcsTagName"
    ),
    "detection.enabled"           -> (
      MonorepoReleaseIO.releaseIOMonorepoDetectionEnabled.key.label,
      "releaseIOMonorepoDetectionEnabled"
    ),
    "detection.includeDownstream" -> (
      MonorepoReleaseIO.releaseIOMonorepoDetectionIncludeDownstream.key.label,
      "releaseIOMonorepoDetectionIncludeDownstream"
    ),
    "publish.checks"              -> (
      MonorepoReleaseIO.releaseIOMonorepoPublishChecks.key.label,
      "releaseIOMonorepoPublishChecks"
    )
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

  test("grouped monorepo keys use canonical labels") {
    canonicalLabels.foreach { case (label, (actual, expected)) =>
      assertEquals(actual, expected, label)
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
