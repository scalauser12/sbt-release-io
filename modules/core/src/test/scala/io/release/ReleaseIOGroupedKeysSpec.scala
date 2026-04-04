package io.release

import cats.effect.IO
import munit.CatsEffectSuite
import sbt.SettingKey
import sbt.TaskKey

class ReleaseIOGroupedKeysSpec extends CatsEffectSuite with ReleasePluginIOSpecSupport {

  private def keyLabel[A](key: SettingKey[A]): String = key.key.label
  private def keyLabel[A](key: TaskKey[A]): String    = key.key.label

  private val expectedLabels = Set(
    "releaseIOBehaviorCrossBuild",
    "releaseIOBehaviorSkipPublish",
    "releaseIOBehaviorInteractive",
    "releaseIODefaultsTagExistsAnswer",
    "releaseIODefaultsSnapshotDependenciesAnswer",
    "releaseIODefaultsRemoteCheckFailureAnswer",
    "releaseIODefaultsUpstreamBehindAnswer",
    "releaseIODefaultsPushAnswer",
    "releaseIOPolicyEnableSnapshotDependenciesCheck",
    "releaseIOPolicyEnableRunClean",
    "releaseIOPolicyEnableRunTests",
    "releaseIOPolicyEnableTagging",
    "releaseIOPolicyEnablePublish",
    "releaseIOPolicyEnablePush",
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
    "releaseIOHooksAfterPush",
    "releaseIOVersioningReadVersion",
    "releaseIOVersioningFileContents",
    "releaseIOVersioningFile",
    "releaseIOVersioningUseGlobal",
    "releaseIOVersioningReleaseVersion",
    "releaseIOVersioningNextVersion",
    "releaseIOVersioningBump",
    "releaseIOVcsSign",
    "releaseIOVcsSignOff",
    "releaseIOVcsIgnoreUntrackedFiles",
    "releaseIOVcsRemoteCheckTimeout",
    "releaseIOVcsTagName",
    "releaseIOVcsTagComment",
    "releaseIOVcsReleaseCommitMessage",
    "releaseIOVcsNextCommitMessage",
    "releaseIOPublishAction",
    "releaseIOPublishChecks",
    "releaseIORuntimeCurrentVersion",
    "releaseIODiagnosticsSnapshotDependencies"
  )

  private val actualLabels = Set(
    keyLabel(ReleaseIO.releaseIOBehaviorCrossBuild),
    keyLabel(ReleaseIO.releaseIOBehaviorSkipPublish),
    keyLabel(ReleaseIO.releaseIOBehaviorInteractive),
    keyLabel(ReleaseIO.releaseIODefaultsTagExistsAnswer),
    keyLabel(ReleaseIO.releaseIODefaultsSnapshotDependenciesAnswer),
    keyLabel(ReleaseIO.releaseIODefaultsRemoteCheckFailureAnswer),
    keyLabel(ReleaseIO.releaseIODefaultsUpstreamBehindAnswer),
    keyLabel(ReleaseIO.releaseIODefaultsPushAnswer),
    keyLabel(ReleaseIO.releaseIOPolicyEnableSnapshotDependenciesCheck),
    keyLabel(ReleaseIO.releaseIOPolicyEnableRunClean),
    keyLabel(ReleaseIO.releaseIOPolicyEnableRunTests),
    keyLabel(ReleaseIO.releaseIOPolicyEnableTagging),
    keyLabel(ReleaseIO.releaseIOPolicyEnablePublish),
    keyLabel(ReleaseIO.releaseIOPolicyEnablePush),
    keyLabel(ReleaseIO.releaseIOHooksAfterCleanCheck),
    keyLabel(ReleaseIO.releaseIOHooksBeforeVersionResolution),
    keyLabel(ReleaseIO.releaseIOHooksAfterVersionResolution),
    keyLabel(ReleaseIO.releaseIOHooksBeforeReleaseVersionWrite),
    keyLabel(ReleaseIO.releaseIOHooksAfterReleaseVersionWrite),
    keyLabel(ReleaseIO.releaseIOHooksBeforeReleaseCommit),
    keyLabel(ReleaseIO.releaseIOHooksAfterReleaseCommit),
    keyLabel(ReleaseIO.releaseIOHooksBeforeTag),
    keyLabel(ReleaseIO.releaseIOHooksAfterTag),
    keyLabel(ReleaseIO.releaseIOHooksBeforePublish),
    keyLabel(ReleaseIO.releaseIOHooksAfterPublish),
    keyLabel(ReleaseIO.releaseIOHooksBeforeNextVersionWrite),
    keyLabel(ReleaseIO.releaseIOHooksAfterNextVersionWrite),
    keyLabel(ReleaseIO.releaseIOHooksBeforeNextCommit),
    keyLabel(ReleaseIO.releaseIOHooksAfterNextCommit),
    keyLabel(ReleaseIO.releaseIOHooksBeforePush),
    keyLabel(ReleaseIO.releaseIOHooksAfterPush),
    keyLabel(ReleaseIO.releaseIOVersioningReadVersion),
    keyLabel(ReleaseIO.releaseIOVersioningFileContents),
    keyLabel(ReleaseIO.releaseIOVersioningFile),
    keyLabel(ReleaseIO.releaseIOVersioningUseGlobal),
    keyLabel(ReleaseIO.releaseIOVersioningReleaseVersion),
    keyLabel(ReleaseIO.releaseIOVersioningNextVersion),
    keyLabel(ReleaseIO.releaseIOVersioningBump),
    keyLabel(ReleaseIO.releaseIOVcsSign),
    keyLabel(ReleaseIO.releaseIOVcsSignOff),
    keyLabel(ReleaseIO.releaseIOVcsIgnoreUntrackedFiles),
    keyLabel(ReleaseIO.releaseIOVcsRemoteCheckTimeout),
    keyLabel(ReleaseIO.releaseIOVcsTagName),
    keyLabel(ReleaseIO.releaseIOVcsTagComment),
    keyLabel(ReleaseIO.releaseIOVcsReleaseCommitMessage),
    keyLabel(ReleaseIO.releaseIOVcsNextCommitMessage),
    keyLabel(ReleaseIO.releaseIOPublishAction),
    keyLabel(ReleaseIO.releaseIOPublishChecks),
    keyLabel(ReleaseIO.releaseIORuntimeCurrentVersion),
    keyLabel(ReleaseIO.releaseIODiagnosticsSnapshotDependencies)
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

  test("ReleaseIO exposes the full set of 50 expected public keys") {
    assertEquals(actualLabels, expectedLabels)
    assertEquals(actualLabels.size, 50)
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
        !s"(?m)^\\s+(?:lazy\\s+)?val ${java.util.regex.Pattern.quote(name)}\\b".r
          .findFirstIn(releaseIOSource)
          .isDefined,
        s"Expected $name to be removed from ReleaseIO.scala"
      )
    }
  }

  test("ReleaseIO source has no private forwarding aliases except internal manifest keys") {
    assert(
      !raw"private\[release\]\s+lazy val _releaseIO(?!InternalReleaseHash|InternalReleaseTag)".r
        .findFirstIn(releaseIOSource)
        .isDefined,
      "Expected only internal manifest helper keys to remain as _releaseIO private vals"
    )
  }
}
