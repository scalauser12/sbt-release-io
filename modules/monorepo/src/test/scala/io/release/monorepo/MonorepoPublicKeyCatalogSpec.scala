package io.release.monorepo

import io.release.internal.PublicKeyCatalogSupport.KeyKind
import io.release.TestRepoFiles
import munit.FunSuite

class MonorepoPublicKeyCatalogSpec extends FunSuite {

  private lazy val catalogSource =
    TestRepoFiles.readString(
      "modules/monorepo/src/main/scala/io/release/monorepo/MonorepoPublicKeyCatalog.scala"
    )

  private val expectedLabelsByGroup = Vector(
    "selection"  -> Vector("releaseIOMonorepoSelectionProjects"),
    "behavior"   -> Vector(
      "releaseIOMonorepoBehaviorCrossBuild",
      "releaseIOMonorepoBehaviorSkipTests",
      "releaseIOMonorepoBehaviorSkipPublish",
      "releaseIOMonorepoBehaviorInteractive"
    ),
    "policy"     -> Vector(
      "releaseIOMonorepoPolicyEnableSnapshotDependenciesCheck",
      "releaseIOMonorepoPolicyEnableRunClean",
      "releaseIOMonorepoPolicyEnableRunTests",
      "releaseIOMonorepoPolicyEnableTagging",
      "releaseIOMonorepoPolicyEnablePublish",
      "releaseIOMonorepoPolicyEnablePush"
    ),
    "hooks"      -> Vector(
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
      "releaseIOMonorepoHooksAfterPush"
    ),
    "versioning" -> Vector(
      "releaseIOMonorepoVersioningFile",
      "releaseIOMonorepoVersioningReadVersion",
      "releaseIOMonorepoVersioningFileContents"
    ),
    "detection"  -> Vector(
      "releaseIOMonorepoDetectionEnabled",
      "releaseIOMonorepoDetectionIncludeDownstream",
      "releaseIOMonorepoDetectionChangeDetector",
      "releaseIOMonorepoDetectionExcludes",
      "releaseIOMonorepoDetectionSharedPaths"
    ),
    "vcs"        -> Vector(
      "releaseIOMonorepoVcsTagName",
      "releaseIOMonorepoVcsTagComment",
      "releaseIOMonorepoVcsReleaseCommitMessage",
      "releaseIOMonorepoVcsNextCommitMessage"
    ),
    "publish"    -> Vector("releaseIOMonorepoPublishChecks")
  )

  test(
    "publicEntries define the full monorepo public key inventory with stable groups and labels"
  ) {
    val actualLabelsByGroup =
      MonorepoPublicKeyCatalog.publicEntries
        .groupBy(_.group)
        .map { case (group, entries) => group -> entries.map(_.label).toSet }
        .toMap

    assertEquals(
      actualLabelsByGroup,
      expectedLabelsByGroup.map { case (group, labels) => group -> labels.toSet }.toMap
    )
    assertEquals(
      MonorepoPublicKeyCatalog.publicEntries.map(_.label),
      expectedLabelsByGroup.flatMap(_._2)
    )

    val labels = MonorepoPublicKeyCatalog.publicEntries.map(_.label)
    assertEquals(labels.distinct.size, labels.size)
    assertEquals(labels.size, 43)
  }

  test("publicEntries preserve monorepo setting-key kinds") {
    MonorepoPublicKeyCatalog.publicEntries.foreach { entry =>
      assertEquals(entry.kind, KeyKind.Setting, entry.label)
    }
  }

  test("grouped public key inventories concatenate to the same publicEntries sequence") {
    assertEquals(
      MonorepoPublicKeyCatalog.publicEntries,
      MonorepoSelectionPublicKeys.publicEntries ++
        MonorepoBehaviorPublicKeys.publicEntries ++
        MonorepoPolicyPublicKeys.publicEntries ++
        MonorepoHookPublicKeys.publicEntries ++
        MonorepoVersioningPublicKeys.publicEntries ++
        MonorepoDetectionPublicKeys.publicEntries ++
        MonorepoVcsPublicKeys.publicEntries ++
        MonorepoPublishPublicKeys.publicEntries
    )
  }

  test("source cleanup - top-level catalog is inventory-only") {
    assert(!catalogSource.contains("setting("))
    assert(!catalogSource.contains("task("))
  }
}
