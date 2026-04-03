package io.release

import cats.effect.IO
import munit.CatsEffectSuite

class ReleaseIOGroupedKeysSpec extends CatsEffectSuite with ReleasePluginIOSpecSupport {

  private val canonicalLabels = Seq(
    "behavior.crossBuild"              -> (
      ReleaseIO.releaseIOBehaviorCrossBuild.key.label,
      "releaseIOBehaviorCrossBuild"
    ),
    "behavior.skipPublish"             -> (
      ReleaseIO.releaseIOBehaviorSkipPublish.key.label,
      "releaseIOBehaviorSkipPublish"
    ),
    "defaults.tagExists"               -> (
      ReleaseIO.releaseIODefaultsTagExistsAnswer.key.label,
      "releaseIODefaultsTagExistsAnswer"
    ),
    "policy.push"                      -> (ReleaseIO.releaseIOPolicyEnablePush.key.label, "releaseIOPolicyEnablePush"),
    "hooks.beforeTag"                  -> (ReleaseIO.releaseIOHooksBeforeTag.key.label, "releaseIOHooksBeforeTag"),
    "versioning.file"                  -> (ReleaseIO.releaseIOVersioningFile.key.label, "releaseIOVersioningFile"),
    "versioning.readVersion"           -> (
      ReleaseIO.releaseIOVersioningReadVersion.key.label,
      "releaseIOVersioningReadVersion"
    ),
    "vcs.tagName"                      -> (ReleaseIO.releaseIOVcsTagName.key.label, "releaseIOVcsTagName"),
    "publish.action"                   -> (ReleaseIO.releaseIOPublishAction.key.label, "releaseIOPublishAction"),
    "runtime.currentVersion"           -> (
      ReleaseIO.releaseIORuntimeCurrentVersion.key.label,
      "releaseIORuntimeCurrentVersion"
    ),
    "diagnostics.snapshotDependencies" -> (
      ReleaseIO.releaseIODiagnosticsSnapshotDependencies.key.label,
      "releaseIODiagnosticsSnapshotDependencies"
    )
  )

  private val removedAliases = Seq(
    "releaseIOCrossBuild",
    "releaseIOSkipPublish",
    "releaseIOInteractive",
    "releaseIODefaultTagExistsAnswer",
    "releaseIODefaultSnapshotDependenciesAnswer",
    "releaseIODefaultRemoteCheckFailureAnswer",
    "releaseIODefaultUpstreamBehindAnswer",
    "releaseIODefaultPushAnswer",
    "releaseIOEnableSnapshotDependenciesCheck",
    "releaseIOEnableRunClean",
    "releaseIOEnableRunTests",
    "releaseIOEnableTagging",
    "releaseIOEnablePublish",
    "releaseIOEnablePush",
    "releaseIOAfterCleanCheckHooks",
    "releaseIOBeforeVersionResolutionHooks",
    "releaseIOAfterVersionResolutionHooks",
    "releaseIOBeforeReleaseVersionWriteHooks",
    "releaseIOAfterReleaseVersionWriteHooks",
    "releaseIOBeforeReleaseCommitHooks",
    "releaseIOAfterReleaseCommitHooks",
    "releaseIOBeforeTagHooks",
    "releaseIOAfterTagHooks",
    "releaseIOBeforePublishHooks",
    "releaseIOAfterPublishHooks",
    "releaseIOBeforeNextVersionWriteHooks",
    "releaseIOAfterNextVersionWriteHooks",
    "releaseIOBeforeNextCommitHooks",
    "releaseIOAfterNextCommitHooks",
    "releaseIOBeforePushHooks",
    "releaseIOAfterPushHooks",
    "releaseIOReadVersion",
    "releaseIOVersionFileContents",
    "releaseIOVersionFile",
    "releaseIOUseGlobalVersion",
    "releaseIOIgnoreUntrackedFiles",
    "releaseIORuntimeVersion",
    "releaseIOTagName",
    "releaseIOTagComment",
    "releaseIOCommitMessage",
    "releaseIONextCommitMessage",
    "releaseIOVersion",
    "releaseIONextVersion",
    "releaseIOVersionBump",
    "releaseIOSnapshotDependencies",
    "releaseIOPublishArtifactsAction",
    "releaseIOPublishArtifactsChecks"
  )

  private lazy val releaseIOSource =
    TestRepoFiles.readString("modules/core/src/main/scala/io/release/ReleaseIO.scala")

  test("grouped core keys use canonical labels") {
    canonicalLabels.foreach { case (label, (actual, expected)) =>
      assertEquals(actual, expected, label)
    }
  }

  test("grouped core settings resolve expected defaults") {
    stateResource("release-io-grouped-keys", HookFriendlyPlugin).use { loaded =>
      IO {
        val extracted = TestkitSbtCompat.extract(loaded.state)

        assertEquals(extracted.get(ReleaseIO.releaseIOBehaviorCrossBuild), false)
        assertEquals(extracted.get(ReleaseIO.releaseIOBehaviorSkipPublish), false)
        assertEquals(extracted.get(ReleaseIO.releaseIOBehaviorInteractive), false)
        assertEquals(extracted.get(ReleaseIO.releaseIODefaultsTagExistsAnswer), None)
        assertEquals(extracted.get(ReleaseIO.releaseIOPolicyEnablePush), true)
        assertEquals(extracted.get(ReleaseIO.releaseIOHooksBeforeTag), Seq.empty)
      }
    }
  }

  test("ReleaseIO source no longer defines deprecated alias vals") {
    assert(!releaseIOSource.contains("@deprecated("))
    removedAliases.foreach { name =>
      assert(
        !releaseIOSource.contains(s"val $name"),
        s"Expected $name to be removed from ReleaseIO.scala"
      )
    }
  }
}
