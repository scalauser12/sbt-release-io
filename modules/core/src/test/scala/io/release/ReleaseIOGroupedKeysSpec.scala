package io.release

import cats.effect.IO
import munit.CatsEffectSuite
import sbt.Extracted
import sbt.SettingKey

import scala.annotation.nowarn

@nowarn("cat=deprecation")
class ReleaseIOGroupedKeysSpec extends CatsEffectSuite with ReleasePluginIOSpecSupport {

  private val aliasPairs: Seq[(String, AnyRef, AnyRef)] = Seq(
    "behavior.crossBuild"              -> (ReleaseIO.releaseIOCrossBuild, ReleaseIO.releaseIOBehaviorCrossBuild),
    "behavior.skipPublish"             -> (
      ReleaseIO.releaseIOSkipPublish,
      ReleaseIO.releaseIOBehaviorSkipPublish
    ),
    "behavior.interactive"             -> (
      ReleaseIO.releaseIOInteractive,
      ReleaseIO.releaseIOBehaviorInteractive
    ),
    "defaults.tagExists"               -> (
      ReleaseIO.releaseIODefaultTagExistsAnswer,
      ReleaseIO.releaseIODefaultsTagExistsAnswer
    ),
    "defaults.snapshotDependencies"    -> (
      ReleaseIO.releaseIODefaultSnapshotDependenciesAnswer,
      ReleaseIO.releaseIODefaultsSnapshotDependenciesAnswer
    ),
    "defaults.remoteCheckFailure"      -> (
      ReleaseIO.releaseIODefaultRemoteCheckFailureAnswer,
      ReleaseIO.releaseIODefaultsRemoteCheckFailureAnswer
    ),
    "defaults.upstreamBehind"          -> (
      ReleaseIO.releaseIODefaultUpstreamBehindAnswer,
      ReleaseIO.releaseIODefaultsUpstreamBehindAnswer
    ),
    "defaults.push"                    -> (
      ReleaseIO.releaseIODefaultPushAnswer,
      ReleaseIO.releaseIODefaultsPushAnswer
    ),
    "policy.snapshotDependencies"      -> (
      ReleaseIO.releaseIOEnableSnapshotDependenciesCheck,
      ReleaseIO.releaseIOPolicyEnableSnapshotDependenciesCheck
    ),
    "policy.runClean"                  -> (
      ReleaseIO.releaseIOEnableRunClean,
      ReleaseIO.releaseIOPolicyEnableRunClean
    ),
    "policy.runTests"                  -> (
      ReleaseIO.releaseIOEnableRunTests,
      ReleaseIO.releaseIOPolicyEnableRunTests
    ),
    "policy.tagging"                   -> (ReleaseIO.releaseIOEnableTagging, ReleaseIO.releaseIOPolicyEnableTagging),
    "policy.publish"                   -> (ReleaseIO.releaseIOEnablePublish, ReleaseIO.releaseIOPolicyEnablePublish),
    "policy.push"                      -> (ReleaseIO.releaseIOEnablePush, ReleaseIO.releaseIOPolicyEnablePush),
    "hooks.afterCleanCheck"            -> (
      ReleaseIO.releaseIOAfterCleanCheckHooks,
      ReleaseIO.releaseIOHooksAfterCleanCheck
    ),
    "hooks.beforeVersionResolution"    -> (
      ReleaseIO.releaseIOBeforeVersionResolutionHooks,
      ReleaseIO.releaseIOHooksBeforeVersionResolution
    ),
    "hooks.afterVersionResolution"     -> (
      ReleaseIO.releaseIOAfterVersionResolutionHooks,
      ReleaseIO.releaseIOHooksAfterVersionResolution
    ),
    "hooks.beforeReleaseVersionWrite"  -> (
      ReleaseIO.releaseIOBeforeReleaseVersionWriteHooks,
      ReleaseIO.releaseIOHooksBeforeReleaseVersionWrite
    ),
    "hooks.afterReleaseVersionWrite"   -> (
      ReleaseIO.releaseIOAfterReleaseVersionWriteHooks,
      ReleaseIO.releaseIOHooksAfterReleaseVersionWrite
    ),
    "hooks.beforeReleaseCommit"        -> (
      ReleaseIO.releaseIOBeforeReleaseCommitHooks,
      ReleaseIO.releaseIOHooksBeforeReleaseCommit
    ),
    "hooks.afterReleaseCommit"         -> (
      ReleaseIO.releaseIOAfterReleaseCommitHooks,
      ReleaseIO.releaseIOHooksAfterReleaseCommit
    ),
    "hooks.beforeTag"                  -> (ReleaseIO.releaseIOBeforeTagHooks, ReleaseIO.releaseIOHooksBeforeTag),
    "hooks.afterTag"                   -> (ReleaseIO.releaseIOAfterTagHooks, ReleaseIO.releaseIOHooksAfterTag),
    "hooks.beforePublish"              -> (
      ReleaseIO.releaseIOBeforePublishHooks,
      ReleaseIO.releaseIOHooksBeforePublish
    ),
    "hooks.afterPublish"               -> (
      ReleaseIO.releaseIOAfterPublishHooks,
      ReleaseIO.releaseIOHooksAfterPublish
    ),
    "hooks.beforeNextVersionWrite"     -> (
      ReleaseIO.releaseIOBeforeNextVersionWriteHooks,
      ReleaseIO.releaseIOHooksBeforeNextVersionWrite
    ),
    "hooks.afterNextVersionWrite"      -> (
      ReleaseIO.releaseIOAfterNextVersionWriteHooks,
      ReleaseIO.releaseIOHooksAfterNextVersionWrite
    ),
    "hooks.beforeNextCommit"           -> (
      ReleaseIO.releaseIOBeforeNextCommitHooks,
      ReleaseIO.releaseIOHooksBeforeNextCommit
    ),
    "hooks.afterNextCommit"            -> (
      ReleaseIO.releaseIOAfterNextCommitHooks,
      ReleaseIO.releaseIOHooksAfterNextCommit
    ),
    "hooks.beforePush"                 -> (ReleaseIO.releaseIOBeforePushHooks, ReleaseIO.releaseIOHooksBeforePush),
    "hooks.afterPush"                  -> (ReleaseIO.releaseIOAfterPushHooks, ReleaseIO.releaseIOHooksAfterPush),
    "versioning.readVersion"           -> (
      ReleaseIO.releaseIOReadVersion,
      ReleaseIO.releaseIOVersioningReadVersion
    ),
    "versioning.fileContents"          -> (
      ReleaseIO.releaseIOVersionFileContents,
      ReleaseIO.releaseIOVersioningFileContents
    ),
    "versioning.file"                  -> (ReleaseIO.releaseIOVersionFile, ReleaseIO.releaseIOVersioningFile),
    "versioning.useGlobal"             -> (
      ReleaseIO.releaseIOUseGlobalVersion,
      ReleaseIO.releaseIOVersioningUseGlobal
    ),
    "versioning.releaseVersion"        -> (
      ReleaseIO.releaseIOVersion,
      ReleaseIO.releaseIOVersioningReleaseVersion
    ),
    "versioning.nextVersion"           -> (
      ReleaseIO.releaseIONextVersion,
      ReleaseIO.releaseIOVersioningNextVersion
    ),
    "versioning.bump"                  -> (ReleaseIO.releaseIOVersionBump, ReleaseIO.releaseIOVersioningBump),
    "vcs.ignoreUntracked"              -> (
      ReleaseIO.releaseIOIgnoreUntrackedFiles,
      ReleaseIO.releaseIOVcsIgnoreUntrackedFiles
    ),
    "vcs.tagName"                      -> (ReleaseIO.releaseIOTagName, ReleaseIO.releaseIOVcsTagName),
    "vcs.tagComment"                   -> (ReleaseIO.releaseIOTagComment, ReleaseIO.releaseIOVcsTagComment),
    "vcs.releaseCommitMessage"         -> (
      ReleaseIO.releaseIOCommitMessage,
      ReleaseIO.releaseIOVcsReleaseCommitMessage
    ),
    "vcs.nextCommitMessage"            -> (
      ReleaseIO.releaseIONextCommitMessage,
      ReleaseIO.releaseIOVcsNextCommitMessage
    ),
    "publish.action"                   -> (
      ReleaseIO.releaseIOPublishArtifactsAction,
      ReleaseIO.releaseIOPublishAction
    ),
    "publish.checks"                   -> (
      ReleaseIO.releaseIOPublishArtifactsChecks,
      ReleaseIO.releaseIOPublishChecks
    ),
    "runtime.currentVersion"           -> (
      ReleaseIO.releaseIORuntimeVersion,
      ReleaseIO.releaseIORuntimeCurrentVersion
    ),
    "diagnostics.snapshotDependencies" -> (
      ReleaseIO.releaseIOSnapshotDependencies,
      ReleaseIO.releaseIODiagnosticsSnapshotDependencies
    )
  ).map { entry =>
    val label  = entry._1
    val oldKey = entry._2._1
    val newKey = entry._2._2

    (label, oldKey.asInstanceOf[AnyRef], newKey.asInstanceOf[AnyRef])
  }

  private val defaultParityChecks: Seq[(String, Extracted => Unit)] = Seq(
    settingParity(
      "behavior.crossBuild",
      ReleaseIO.releaseIOCrossBuild,
      ReleaseIO.releaseIOBehaviorCrossBuild
    ),
    settingParity(
      "behavior.skipPublish",
      ReleaseIO.releaseIOSkipPublish,
      ReleaseIO.releaseIOBehaviorSkipPublish
    ),
    settingParity(
      "behavior.interactive",
      ReleaseIO.releaseIOInteractive,
      ReleaseIO.releaseIOBehaviorInteractive
    ),
    settingParity(
      "defaults.tagExists",
      ReleaseIO.releaseIODefaultTagExistsAnswer,
      ReleaseIO.releaseIODefaultsTagExistsAnswer
    ),
    settingParity(
      "defaults.snapshotDependencies",
      ReleaseIO.releaseIODefaultSnapshotDependenciesAnswer,
      ReleaseIO.releaseIODefaultsSnapshotDependenciesAnswer
    ),
    settingParity(
      "policy.publish",
      ReleaseIO.releaseIOEnablePublish,
      ReleaseIO.releaseIOPolicyEnablePublish
    ),
    settingParity(
      "hooks.beforeTag",
      ReleaseIO.releaseIOBeforeTagHooks,
      ReleaseIO.releaseIOHooksBeforeTag
    )
  )

  private val deprecatedRenames: Seq[(String, String)] = Seq(
    "releaseIOCrossBuild"                        -> "releaseIOBehaviorCrossBuild",
    "releaseIOSkipPublish"                       -> "releaseIOBehaviorSkipPublish",
    "releaseIOInteractive"                       -> "releaseIOBehaviorInteractive",
    "releaseIODefaultTagExistsAnswer"            -> "releaseIODefaultsTagExistsAnswer",
    "releaseIODefaultSnapshotDependenciesAnswer" -> "releaseIODefaultsSnapshotDependenciesAnswer",
    "releaseIODefaultRemoteCheckFailureAnswer"   -> "releaseIODefaultsRemoteCheckFailureAnswer",
    "releaseIODefaultUpstreamBehindAnswer"       -> "releaseIODefaultsUpstreamBehindAnswer",
    "releaseIODefaultPushAnswer"                 -> "releaseIODefaultsPushAnswer",
    "releaseIOEnableSnapshotDependenciesCheck"   -> "releaseIOPolicyEnableSnapshotDependenciesCheck",
    "releaseIOEnableRunClean"                    -> "releaseIOPolicyEnableRunClean",
    "releaseIOEnableRunTests"                    -> "releaseIOPolicyEnableRunTests",
    "releaseIOEnableTagging"                     -> "releaseIOPolicyEnableTagging",
    "releaseIOEnablePublish"                     -> "releaseIOPolicyEnablePublish",
    "releaseIOEnablePush"                        -> "releaseIOPolicyEnablePush",
    "releaseIOAfterCleanCheckHooks"              -> "releaseIOHooksAfterCleanCheck",
    "releaseIOBeforeVersionResolutionHooks"      -> "releaseIOHooksBeforeVersionResolution",
    "releaseIOAfterVersionResolutionHooks"       -> "releaseIOHooksAfterVersionResolution",
    "releaseIOBeforeReleaseVersionWriteHooks"    -> "releaseIOHooksBeforeReleaseVersionWrite",
    "releaseIOAfterReleaseVersionWriteHooks"     -> "releaseIOHooksAfterReleaseVersionWrite",
    "releaseIOBeforeReleaseCommitHooks"          -> "releaseIOHooksBeforeReleaseCommit",
    "releaseIOAfterReleaseCommitHooks"           -> "releaseIOHooksAfterReleaseCommit",
    "releaseIOBeforeTagHooks"                    -> "releaseIOHooksBeforeTag",
    "releaseIOAfterTagHooks"                     -> "releaseIOHooksAfterTag",
    "releaseIOBeforePublishHooks"                -> "releaseIOHooksBeforePublish",
    "releaseIOAfterPublishHooks"                 -> "releaseIOHooksAfterPublish",
    "releaseIOBeforeNextVersionWriteHooks"       -> "releaseIOHooksBeforeNextVersionWrite",
    "releaseIOAfterNextVersionWriteHooks"        -> "releaseIOHooksAfterNextVersionWrite",
    "releaseIOBeforeNextCommitHooks"             -> "releaseIOHooksBeforeNextCommit",
    "releaseIOAfterNextCommitHooks"              -> "releaseIOHooksAfterNextCommit",
    "releaseIOBeforePushHooks"                   -> "releaseIOHooksBeforePush",
    "releaseIOAfterPushHooks"                    -> "releaseIOHooksAfterPush",
    "releaseIOReadVersion"                       -> "releaseIOVersioningReadVersion",
    "releaseIOVersionFileContents"               -> "releaseIOVersioningFileContents",
    "releaseIOVersionFile"                       -> "releaseIOVersioningFile",
    "releaseIOUseGlobalVersion"                  -> "releaseIOVersioningUseGlobal",
    "releaseIOIgnoreUntrackedFiles"              -> "releaseIOVcsIgnoreUntrackedFiles",
    "releaseIORuntimeVersion"                    -> "releaseIORuntimeCurrentVersion",
    "releaseIOTagName"                           -> "releaseIOVcsTagName",
    "releaseIOTagComment"                        -> "releaseIOVcsTagComment",
    "releaseIOCommitMessage"                     -> "releaseIOVcsReleaseCommitMessage",
    "releaseIONextCommitMessage"                 -> "releaseIOVcsNextCommitMessage",
    "releaseIOVersion"                           -> "releaseIOVersioningReleaseVersion",
    "releaseIONextVersion"                       -> "releaseIOVersioningNextVersion",
    "releaseIOVersionBump"                       -> "releaseIOVersioningBump",
    "releaseIOSnapshotDependencies"              -> "releaseIODiagnosticsSnapshotDependencies",
    "releaseIOPublishArtifactsAction"            -> "releaseIOPublishAction",
    "releaseIOPublishArtifactsChecks"            -> "releaseIOPublishChecks"
  )

  private lazy val releaseIOSource: String =
    TestRepoFiles.readString("modules/core/src/main/scala/io/release/ReleaseIO.scala")

  test("grouped core names alias the same key instances") {
    aliasPairs.foreach { case (label, oldKey, newKey) =>
      assert(
        oldKey eq newKey,
        s"Expected $label to reuse the same key singleton"
      )
    }
  }

  test("grouped core setting names resolve identical defaults") {
    stateResource("release-io-grouped-keys", HookFriendlyPlugin).use { loaded =>
      IO {
        val extracted = TestkitSbtCompat.extract(loaded.state)
        defaultParityChecks.foreach { case (_, check) => check(extracted) }
      }
    }
  }

  test("deprecated core names point to grouped replacements") {
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
      .findFirstMatchIn(releaseIOSource)
      .map(_.group(1))
}
