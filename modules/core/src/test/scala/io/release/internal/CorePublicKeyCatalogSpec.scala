package io.release.internal

import munit.FunSuite

class CorePublicKeyCatalogSpec extends FunSuite {
  import PublicKeyCatalogSupport.KeyKind

  private val expectedLabelsByGroup = Vector(
    "behavior"    -> Vector(
      "releaseIOBehaviorCrossBuild",
      "releaseIOBehaviorSkipPublish",
      "releaseIOBehaviorInteractive"
    ),
    "defaults"    -> Vector(
      "releaseIODefaultsTagExistsAnswer",
      "releaseIODefaultsSnapshotDependenciesAnswer",
      "releaseIODefaultsRemoteCheckFailureAnswer",
      "releaseIODefaultsUpstreamBehindAnswer",
      "releaseIODefaultsPushAnswer"
    ),
    "policy"      -> Vector(
      "releaseIOPolicyEnableSnapshotDependenciesCheck",
      "releaseIOPolicyEnableRunClean",
      "releaseIOPolicyEnableRunTests",
      "releaseIOPolicyEnableTagging",
      "releaseIOPolicyEnablePublish",
      "releaseIOPolicyEnablePush"
    ),
    "hooks"       -> Vector(
      "releaseIOHooksAfterCleanCheck",
      "releaseIOHooksBeforeVersionResolution",
      "releaseIOHooksAfterVersionResolution",
      "releaseIOHooksBeforeReleaseVersionWrite",
      "releaseIOHooksAfterReleaseVersionWrite",
      "releaseIOHooksBeforeReleaseCommit",
      "releaseIOHooksAfterReleaseCommit",
      "releaseIOHooksBeforeTag",
      "releaseIOHooksAfterTag",
      "releaseIOHooksBeforePublish",
      "releaseIOHooksAfterPublish",
      "releaseIOHooksBeforeNextVersionWrite",
      "releaseIOHooksAfterNextVersionWrite",
      "releaseIOHooksBeforeNextCommit",
      "releaseIOHooksAfterNextCommit",
      "releaseIOHooksBeforePush",
      "releaseIOHooksAfterPush"
    ),
    "versioning"  -> Vector(
      "releaseIOVersioningReadVersion",
      "releaseIOVersioningFileContents",
      "releaseIOVersioningFile",
      "releaseIOVersioningUseGlobal",
      "releaseIOVersioningReleaseVersion",
      "releaseIOVersioningNextVersion",
      "releaseIOVersioningBump"
    ),
    "vcs"         -> Vector(
      "releaseIOVcsSign",
      "releaseIOVcsSignOff",
      "releaseIOVcsIgnoreUntrackedFiles",
      "releaseIOVcsRemoteCheckTimeout",
      "releaseIOVcsTagName",
      "releaseIOVcsTagComment",
      "releaseIOVcsReleaseCommitMessage",
      "releaseIOVcsNextCommitMessage"
    ),
    "publish"     -> Vector(
      "releaseIOPublishAction",
      "releaseIOPublishChecks"
    ),
    "runtime"     -> Vector("releaseIORuntimeCurrentVersion"),
    "diagnostics" -> Vector("releaseIODiagnosticsSnapshotDependencies")
  )

  private val expectedTaskLabels = Set(
    "releaseIOVersioningReleaseVersion",
    "releaseIOVersioningNextVersion",
    "releaseIOVersioningBump",
    "releaseIOVcsTagName",
    "releaseIOVcsTagComment",
    "releaseIOVcsReleaseCommitMessage",
    "releaseIOVcsNextCommitMessage",
    "releaseIOPublishAction",
    "releaseIORuntimeCurrentVersion",
    "releaseIODiagnosticsSnapshotDependencies"
  )

  test("publicEntries define the full core public key inventory with stable groups and labels") {
    val actualLabelsByGroup =
      CorePublicKeyCatalog.publicEntries
        .groupBy(_.group)
        .map { case (group, entries) => group -> entries.map(_.label).toSet }
        .toMap

    assertEquals(
      actualLabelsByGroup,
      expectedLabelsByGroup.map { case (group, labels) => group -> labels.toSet }.toMap
    )
    assertEquals(
      CorePublicKeyCatalog.publicEntries.map(_.label),
      expectedLabelsByGroup.flatMap(_._2)
    )

    val labels = CorePublicKeyCatalog.publicEntries.map(_.label)
    assertEquals(labels.distinct.size, labels.size)
    assertEquals(labels.size, 50)
  }

  test("publicEntries preserve core setting and transient task kinds") {
    val kindsByLabel =
      CorePublicKeyCatalog.publicEntries.iterator.map(entry => entry.label -> entry.kind).toMap

    expectedLabelsByGroup.flatMap(_._2).foreach { label =>
      val expectedKind =
        if (expectedTaskLabels.contains(label)) KeyKind.Task(isTransient = true)
        else KeyKind.Setting
      assertEquals(kindsByLabel(label), expectedKind, label)
    }
  }
}
