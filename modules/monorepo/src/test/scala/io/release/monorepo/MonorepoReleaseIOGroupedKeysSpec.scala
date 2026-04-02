package io.release.monorepo

import cats.effect.IO
import io.release.TestRepoFiles
import munit.CatsEffectSuite
import sbt.Extracted
import sbt.SettingKey

import scala.annotation.nowarn

@nowarn("cat=deprecation")
class MonorepoReleaseIOGroupedKeysSpec
    extends CatsEffectSuite
    with MonorepoReleasePluginSpecSupport {

  private val aliasPairs: Seq[(String, AnyRef, AnyRef)] = Seq(
    "selection.projects"              -> (
      MonorepoReleaseIO.releaseIOMonorepoProjects,
      MonorepoReleaseIO.releaseIOMonorepoSelectionProjects
    ),
    "behavior.crossBuild"             -> (
      MonorepoReleaseIO.releaseIOMonorepoCrossBuild,
      MonorepoReleaseIO.releaseIOMonorepoBehaviorCrossBuild
    ),
    "behavior.skipTests"              -> (
      MonorepoReleaseIO.releaseIOMonorepoSkipTests,
      MonorepoReleaseIO.releaseIOMonorepoBehaviorSkipTests
    ),
    "behavior.skipPublish"            -> (
      MonorepoReleaseIO.releaseIOMonorepoSkipPublish,
      MonorepoReleaseIO.releaseIOMonorepoBehaviorSkipPublish
    ),
    "behavior.interactive"            -> (
      MonorepoReleaseIO.releaseIOMonorepoInteractive,
      MonorepoReleaseIO.releaseIOMonorepoBehaviorInteractive
    ),
    "policy.snapshotDependencies"     -> (
      MonorepoReleaseIO.releaseIOMonorepoEnableSnapshotDependenciesCheck,
      MonorepoReleaseIO.releaseIOMonorepoPolicyEnableSnapshotDependenciesCheck
    ),
    "policy.runClean"                 -> (
      MonorepoReleaseIO.releaseIOMonorepoEnableRunClean,
      MonorepoReleaseIO.releaseIOMonorepoPolicyEnableRunClean
    ),
    "policy.runTests"                 -> (
      MonorepoReleaseIO.releaseIOMonorepoEnableRunTests,
      MonorepoReleaseIO.releaseIOMonorepoPolicyEnableRunTests
    ),
    "policy.tagging"                  -> (
      MonorepoReleaseIO.releaseIOMonorepoEnableTagging,
      MonorepoReleaseIO.releaseIOMonorepoPolicyEnableTagging
    ),
    "policy.publish"                  -> (
      MonorepoReleaseIO.releaseIOMonorepoEnablePublish,
      MonorepoReleaseIO.releaseIOMonorepoPolicyEnablePublish
    ),
    "policy.push"                     -> (
      MonorepoReleaseIO.releaseIOMonorepoEnablePush,
      MonorepoReleaseIO.releaseIOMonorepoPolicyEnablePush
    ),
    "hooks.afterCleanCheck"           -> (
      MonorepoReleaseIO.releaseIOMonorepoAfterCleanCheckHooks,
      MonorepoReleaseIO.releaseIOMonorepoHooksAfterCleanCheck
    ),
    "hooks.beforeSelection"           -> (
      MonorepoReleaseIO.releaseIOMonorepoBeforeSelectionHooks,
      MonorepoReleaseIO.releaseIOMonorepoHooksBeforeSelection
    ),
    "hooks.afterSelection"            -> (
      MonorepoReleaseIO.releaseIOMonorepoAfterSelectionHooks,
      MonorepoReleaseIO.releaseIOMonorepoHooksAfterSelection
    ),
    "hooks.beforeVersionResolution"   -> (
      MonorepoReleaseIO.releaseIOMonorepoBeforeVersionResolutionHooks,
      MonorepoReleaseIO.releaseIOMonorepoHooksBeforeVersionResolution
    ),
    "hooks.afterVersionResolution"    -> (
      MonorepoReleaseIO.releaseIOMonorepoAfterVersionResolutionHooks,
      MonorepoReleaseIO.releaseIOMonorepoHooksAfterVersionResolution
    ),
    "hooks.beforeReleaseVersionWrite" -> (
      MonorepoReleaseIO.releaseIOMonorepoBeforeReleaseVersionWriteHooks,
      MonorepoReleaseIO.releaseIOMonorepoHooksBeforeReleaseVersionWrite
    ),
    "hooks.afterReleaseVersionWrite"  -> (
      MonorepoReleaseIO.releaseIOMonorepoAfterReleaseVersionWriteHooks,
      MonorepoReleaseIO.releaseIOMonorepoHooksAfterReleaseVersionWrite
    ),
    "hooks.beforeReleaseCommit"       -> (
      MonorepoReleaseIO.releaseIOMonorepoBeforeReleaseCommitHooks,
      MonorepoReleaseIO.releaseIOMonorepoHooksBeforeReleaseCommit
    ),
    "hooks.afterReleaseCommit"        -> (
      MonorepoReleaseIO.releaseIOMonorepoAfterReleaseCommitHooks,
      MonorepoReleaseIO.releaseIOMonorepoHooksAfterReleaseCommit
    ),
    "hooks.beforeTag"                 -> (
      MonorepoReleaseIO.releaseIOMonorepoBeforeTagHooks,
      MonorepoReleaseIO.releaseIOMonorepoHooksBeforeTag
    ),
    "hooks.afterTag"                  -> (
      MonorepoReleaseIO.releaseIOMonorepoAfterTagHooks,
      MonorepoReleaseIO.releaseIOMonorepoHooksAfterTag
    ),
    "hooks.beforePublish"             -> (
      MonorepoReleaseIO.releaseIOMonorepoBeforePublishHooks,
      MonorepoReleaseIO.releaseIOMonorepoHooksBeforePublish
    ),
    "hooks.afterPublish"              -> (
      MonorepoReleaseIO.releaseIOMonorepoAfterPublishHooks,
      MonorepoReleaseIO.releaseIOMonorepoHooksAfterPublish
    ),
    "hooks.beforeNextVersionWrite"    -> (
      MonorepoReleaseIO.releaseIOMonorepoBeforeNextVersionWriteHooks,
      MonorepoReleaseIO.releaseIOMonorepoHooksBeforeNextVersionWrite
    ),
    "hooks.afterNextVersionWrite"     -> (
      MonorepoReleaseIO.releaseIOMonorepoAfterNextVersionWriteHooks,
      MonorepoReleaseIO.releaseIOMonorepoHooksAfterNextVersionWrite
    ),
    "hooks.beforeNextCommit"          -> (
      MonorepoReleaseIO.releaseIOMonorepoBeforeNextCommitHooks,
      MonorepoReleaseIO.releaseIOMonorepoHooksBeforeNextCommit
    ),
    "hooks.afterNextCommit"           -> (
      MonorepoReleaseIO.releaseIOMonorepoAfterNextCommitHooks,
      MonorepoReleaseIO.releaseIOMonorepoHooksAfterNextCommit
    ),
    "hooks.beforePush"                -> (
      MonorepoReleaseIO.releaseIOMonorepoBeforePushHooks,
      MonorepoReleaseIO.releaseIOMonorepoHooksBeforePush
    ),
    "hooks.afterPush"                 -> (
      MonorepoReleaseIO.releaseIOMonorepoAfterPushHooks,
      MonorepoReleaseIO.releaseIOMonorepoHooksAfterPush
    ),
    "versioning.file"                 -> (
      MonorepoReleaseIO.releaseIOMonorepoVersionFile,
      MonorepoReleaseIO.releaseIOMonorepoVersioningFile
    ),
    "versioning.readVersion"          -> (
      MonorepoReleaseIO.releaseIOMonorepoReadVersion,
      MonorepoReleaseIO.releaseIOMonorepoVersioningReadVersion
    ),
    "versioning.fileContents"         -> (
      MonorepoReleaseIO.releaseIOMonorepoVersionFileContents,
      MonorepoReleaseIO.releaseIOMonorepoVersioningFileContents
    ),
    "vcs.tagName"                     -> (
      MonorepoReleaseIO.releaseIOMonorepoTagName,
      MonorepoReleaseIO.releaseIOMonorepoVcsTagName
    ),
    "vcs.tagComment"                  -> (
      MonorepoReleaseIO.releaseIOMonorepoTagComment,
      MonorepoReleaseIO.releaseIOMonorepoVcsTagComment
    ),
    "vcs.releaseCommitMessage"        -> (
      MonorepoReleaseIO.releaseIOMonorepoCommitMessage,
      MonorepoReleaseIO.releaseIOMonorepoVcsReleaseCommitMessage
    ),
    "vcs.nextCommitMessage"           -> (
      MonorepoReleaseIO.releaseIOMonorepoNextCommitMessage,
      MonorepoReleaseIO.releaseIOMonorepoVcsNextCommitMessage
    ),
    "detection.enabled"               -> (
      MonorepoReleaseIO.releaseIOMonorepoDetectChanges,
      MonorepoReleaseIO.releaseIOMonorepoDetectionEnabled
    ),
    "detection.includeDownstream"     -> (
      MonorepoReleaseIO.releaseIOMonorepoIncludeDownstream,
      MonorepoReleaseIO.releaseIOMonorepoDetectionIncludeDownstream
    ),
    "detection.changeDetector"        -> (
      MonorepoReleaseIO.releaseIOMonorepoChangeDetector,
      MonorepoReleaseIO.releaseIOMonorepoDetectionChangeDetector
    ),
    "detection.excludes"              -> (
      MonorepoReleaseIO.releaseIOMonorepoDetectChangesExcludes,
      MonorepoReleaseIO.releaseIOMonorepoDetectionExcludes
    ),
    "detection.sharedPaths"           -> (
      MonorepoReleaseIO.releaseIOMonorepoSharedPaths,
      MonorepoReleaseIO.releaseIOMonorepoDetectionSharedPaths
    ),
    "publish.checks"                  -> (
      MonorepoReleaseIO.releaseIOMonorepoPublishArtifactsChecks,
      MonorepoReleaseIO.releaseIOMonorepoPublishChecks
    )
  ).map { entry =>
    val label  = entry._1
    val oldKey = entry._2._1
    val newKey = entry._2._2

    (label, oldKey.asInstanceOf[AnyRef], newKey.asInstanceOf[AnyRef])
  }

  private val defaultParityChecks: Seq[(String, Extracted => Unit)] = Seq(
    settingParity(
      "behavior.skipTests",
      MonorepoReleaseIO.releaseIOMonorepoSkipTests,
      MonorepoReleaseIO.releaseIOMonorepoBehaviorSkipTests
    ),
    settingParity(
      "behavior.skipPublish",
      MonorepoReleaseIO.releaseIOMonorepoSkipPublish,
      MonorepoReleaseIO.releaseIOMonorepoBehaviorSkipPublish
    ),
    settingParity(
      "policy.publish",
      MonorepoReleaseIO.releaseIOMonorepoEnablePublish,
      MonorepoReleaseIO.releaseIOMonorepoPolicyEnablePublish
    ),
    settingParity(
      "hooks.afterCleanCheck",
      MonorepoReleaseIO.releaseIOMonorepoAfterCleanCheckHooks,
      MonorepoReleaseIO.releaseIOMonorepoHooksAfterCleanCheck
    ),
    settingParity(
      "hooks.beforeSelection",
      MonorepoReleaseIO.releaseIOMonorepoBeforeSelectionHooks,
      MonorepoReleaseIO.releaseIOMonorepoHooksBeforeSelection
    ),
    settingParity(
      "publish.checks",
      MonorepoReleaseIO.releaseIOMonorepoPublishArtifactsChecks,
      MonorepoReleaseIO.releaseIOMonorepoPublishChecks
    )
  )

  private val deprecatedRenames: Seq[(String, String)] = Seq(
    "releaseIOMonorepoProjects"                        -> "releaseIOMonorepoSelectionProjects",
    "releaseIOMonorepoEnableSnapshotDependenciesCheck" -> "releaseIOMonorepoPolicyEnableSnapshotDependenciesCheck",
    "releaseIOMonorepoEnableRunClean"                  -> "releaseIOMonorepoPolicyEnableRunClean",
    "releaseIOMonorepoEnableRunTests"                  -> "releaseIOMonorepoPolicyEnableRunTests",
    "releaseIOMonorepoEnableTagging"                   -> "releaseIOMonorepoPolicyEnableTagging",
    "releaseIOMonorepoEnablePublish"                   -> "releaseIOMonorepoPolicyEnablePublish",
    "releaseIOMonorepoEnablePush"                      -> "releaseIOMonorepoPolicyEnablePush",
    "releaseIOMonorepoAfterCleanCheckHooks"            -> "releaseIOMonorepoHooksAfterCleanCheck",
    "releaseIOMonorepoBeforeSelectionHooks"            -> "releaseIOMonorepoHooksBeforeSelection",
    "releaseIOMonorepoAfterSelectionHooks"             -> "releaseIOMonorepoHooksAfterSelection",
    "releaseIOMonorepoBeforeVersionResolutionHooks"    -> "releaseIOMonorepoHooksBeforeVersionResolution",
    "releaseIOMonorepoAfterVersionResolutionHooks"     -> "releaseIOMonorepoHooksAfterVersionResolution",
    "releaseIOMonorepoBeforeReleaseVersionWriteHooks"  -> "releaseIOMonorepoHooksBeforeReleaseVersionWrite",
    "releaseIOMonorepoAfterReleaseVersionWriteHooks"   -> "releaseIOMonorepoHooksAfterReleaseVersionWrite",
    "releaseIOMonorepoBeforeReleaseCommitHooks"        -> "releaseIOMonorepoHooksBeforeReleaseCommit",
    "releaseIOMonorepoAfterReleaseCommitHooks"         -> "releaseIOMonorepoHooksAfterReleaseCommit",
    "releaseIOMonorepoBeforeTagHooks"                  -> "releaseIOMonorepoHooksBeforeTag",
    "releaseIOMonorepoAfterTagHooks"                   -> "releaseIOMonorepoHooksAfterTag",
    "releaseIOMonorepoBeforePublishHooks"              -> "releaseIOMonorepoHooksBeforePublish",
    "releaseIOMonorepoAfterPublishHooks"               -> "releaseIOMonorepoHooksAfterPublish",
    "releaseIOMonorepoBeforeNextVersionWriteHooks"     -> "releaseIOMonorepoHooksBeforeNextVersionWrite",
    "releaseIOMonorepoAfterNextVersionWriteHooks"      -> "releaseIOMonorepoHooksAfterNextVersionWrite",
    "releaseIOMonorepoBeforeNextCommitHooks"           -> "releaseIOMonorepoHooksBeforeNextCommit",
    "releaseIOMonorepoAfterNextCommitHooks"            -> "releaseIOMonorepoHooksAfterNextCommit",
    "releaseIOMonorepoBeforePushHooks"                 -> "releaseIOMonorepoHooksBeforePush",
    "releaseIOMonorepoAfterPushHooks"                  -> "releaseIOMonorepoHooksAfterPush",
    "releaseIOMonorepoVersionFile"                     -> "releaseIOMonorepoVersioningFile",
    "releaseIOMonorepoReadVersion"                     -> "releaseIOMonorepoVersioningReadVersion",
    "releaseIOMonorepoVersionFileContents"             -> "releaseIOMonorepoVersioningFileContents",
    "releaseIOMonorepoTagName"                         -> "releaseIOMonorepoVcsTagName",
    "releaseIOMonorepoTagComment"                      -> "releaseIOMonorepoVcsTagComment",
    "releaseIOMonorepoDetectChanges"                   -> "releaseIOMonorepoDetectionEnabled",
    "releaseIOMonorepoChangeDetector"                  -> "releaseIOMonorepoDetectionChangeDetector",
    "releaseIOMonorepoDetectChangesExcludes"           -> "releaseIOMonorepoDetectionExcludes",
    "releaseIOMonorepoSharedPaths"                     -> "releaseIOMonorepoDetectionSharedPaths",
    "releaseIOMonorepoIncludeDownstream"               -> "releaseIOMonorepoDetectionIncludeDownstream",
    "releaseIOMonorepoCrossBuild"                      -> "releaseIOMonorepoBehaviorCrossBuild",
    "releaseIOMonorepoSkipTests"                       -> "releaseIOMonorepoBehaviorSkipTests",
    "releaseIOMonorepoSkipPublish"                     -> "releaseIOMonorepoBehaviorSkipPublish",
    "releaseIOMonorepoInteractive"                     -> "releaseIOMonorepoBehaviorInteractive",
    "releaseIOMonorepoPublishArtifactsChecks"          -> "releaseIOMonorepoPublishChecks",
    "releaseIOMonorepoCommitMessage"                   -> "releaseIOMonorepoVcsReleaseCommitMessage",
    "releaseIOMonorepoNextCommitMessage"               -> "releaseIOMonorepoVcsNextCommitMessage"
  )

  private lazy val monorepoReleaseIOSource: String =
    TestRepoFiles.readString(
      "modules/monorepo/src/main/scala/io/release/monorepo/MonorepoReleaseIO.scala"
    )

  test("grouped monorepo names alias the same key instances") {
    aliasPairs.foreach { case (label, oldKey, newKey) =>
      assert(
        oldKey eq newKey,
        s"Expected $label to reuse the same key singleton"
      )
    }
  }

  test("grouped monorepo setting names resolve identical defaults") {
    stateResource("monorepo-grouped-keys", HookFriendlyPlugin).use { loaded =>
      IO {
        val extracted = io.release.TestkitSbtCompat.extract(loaded.state)
        defaultParityChecks.foreach { case (_, check) => check(extracted) }
      }
    }
  }

  test("deprecated monorepo names point to grouped replacements") {
    deprecatedRenames.foreach { case (oldName, newName) =>
      assertEquals(
        deprecationMessage(oldName),
        Some(s"Use $newName instead."),
        oldName
      )
    }
  }

  private def settingParity[A](
      label: String,
      oldKey: SettingKey[A],
      newKey: SettingKey[A]
  ): (String, Extracted => Unit) =
    label -> { extracted =>
      assertEquals(extracted.get(oldKey), extracted.get(newKey), label)
    }

  private def deprecationMessage(memberName: String): Option[String] =
    (s"""(?s)@deprecated\\("([^"]+)",\\s*"[^"]*"\\)\\s*val\\s+$memberName\\b""").r
      .findFirstMatchIn(monorepoReleaseIOSource)
      .map(_.group(1))
}
