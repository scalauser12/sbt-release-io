package io.release

import cats.effect.IO
import io.release.internal.CorePublicKeyCatalog
import munit.CatsEffectSuite
import sbt.{SettingKey, TaskKey}

class ReleaseIOGroupedKeysSpec extends CatsEffectSuite with ReleasePluginIOSpecSupport {

  private def groupedKeyRef[A](key: SettingKey[A]): (String, AnyRef) =
    key.key.label -> key.asInstanceOf[AnyRef]

  private def groupedKeyRef[A](key: TaskKey[A]): (String, AnyRef) =
    key.key.label -> key.asInstanceOf[AnyRef]

  private val groupedPublicKeys = Vector(
    groupedKeyRef(ReleaseIO.releaseIOBehaviorCrossBuild),
    groupedKeyRef(ReleaseIO.releaseIOBehaviorSkipPublish),
    groupedKeyRef(ReleaseIO.releaseIOBehaviorInteractive),
    groupedKeyRef(ReleaseIO.releaseIODefaultsTagExistsAnswer),
    groupedKeyRef(ReleaseIO.releaseIODefaultsSnapshotDependenciesAnswer),
    groupedKeyRef(ReleaseIO.releaseIODefaultsRemoteCheckFailureAnswer),
    groupedKeyRef(ReleaseIO.releaseIODefaultsUpstreamBehindAnswer),
    groupedKeyRef(ReleaseIO.releaseIODefaultsPushAnswer),
    groupedKeyRef(ReleaseIO.releaseIOPolicyEnableSnapshotDependenciesCheck),
    groupedKeyRef(ReleaseIO.releaseIOPolicyEnableRunClean),
    groupedKeyRef(ReleaseIO.releaseIOPolicyEnableRunTests),
    groupedKeyRef(ReleaseIO.releaseIOPolicyEnableTagging),
    groupedKeyRef(ReleaseIO.releaseIOPolicyEnablePublish),
    groupedKeyRef(ReleaseIO.releaseIOPolicyEnablePush),
    groupedKeyRef(ReleaseIO.releaseIOHooksAfterCleanCheck),
    groupedKeyRef(ReleaseIO.releaseIOHooksBeforeVersionResolution),
    groupedKeyRef(ReleaseIO.releaseIOHooksAfterVersionResolution),
    groupedKeyRef(ReleaseIO.releaseIOHooksBeforeReleaseVersionWrite),
    groupedKeyRef(ReleaseIO.releaseIOHooksAfterReleaseVersionWrite),
    groupedKeyRef(ReleaseIO.releaseIOHooksBeforeReleaseCommit),
    groupedKeyRef(ReleaseIO.releaseIOHooksAfterReleaseCommit),
    groupedKeyRef(ReleaseIO.releaseIOHooksBeforeTag),
    groupedKeyRef(ReleaseIO.releaseIOHooksAfterTag),
    groupedKeyRef(ReleaseIO.releaseIOHooksBeforePublish),
    groupedKeyRef(ReleaseIO.releaseIOHooksAfterPublish),
    groupedKeyRef(ReleaseIO.releaseIOHooksBeforeNextVersionWrite),
    groupedKeyRef(ReleaseIO.releaseIOHooksAfterNextVersionWrite),
    groupedKeyRef(ReleaseIO.releaseIOHooksBeforeNextCommit),
    groupedKeyRef(ReleaseIO.releaseIOHooksAfterNextCommit),
    groupedKeyRef(ReleaseIO.releaseIOHooksBeforePush),
    groupedKeyRef(ReleaseIO.releaseIOHooksAfterPush),
    groupedKeyRef(ReleaseIO.releaseIOVersioningReadVersion),
    groupedKeyRef(ReleaseIO.releaseIOVersioningFileContents),
    groupedKeyRef(ReleaseIO.releaseIOVersioningFile),
    groupedKeyRef(ReleaseIO.releaseIOVersioningUseGlobal),
    groupedKeyRef(ReleaseIO.releaseIOVersioningReleaseVersion),
    groupedKeyRef(ReleaseIO.releaseIOVersioningNextVersion),
    groupedKeyRef(ReleaseIO.releaseIOVersioningBump),
    groupedKeyRef(ReleaseIO.releaseIOVcsSign),
    groupedKeyRef(ReleaseIO.releaseIOVcsSignOff),
    groupedKeyRef(ReleaseIO.releaseIOVcsIgnoreUntrackedFiles),
    groupedKeyRef(ReleaseIO.releaseIOVcsRemoteCheckTimeout),
    groupedKeyRef(ReleaseIO.releaseIOVcsTagName),
    groupedKeyRef(ReleaseIO.releaseIOVcsTagComment),
    groupedKeyRef(ReleaseIO.releaseIOVcsReleaseCommitMessage),
    groupedKeyRef(ReleaseIO.releaseIOVcsNextCommitMessage),
    groupedKeyRef(ReleaseIO.releaseIOPublishAction),
    groupedKeyRef(ReleaseIO.releaseIOPublishChecks),
    groupedKeyRef(ReleaseIO.releaseIORuntimeCurrentVersion),
    groupedKeyRef(ReleaseIO.releaseIODiagnosticsSnapshotDependencies)
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

  test("grouped core keys cover the full catalog and expose exact catalog-backed instances") {
    val groupedByLabel = groupedPublicKeys.toMap

    assertEquals(groupedPublicKeys.map(_._1), CorePublicKeyCatalog.publicEntries.map(_.label))
    assertEquals(groupedByLabel.keySet, CorePublicKeyCatalog.publicEntries.map(_.label).toSet)

    CorePublicKeyCatalog.publicEntries.foreach { entry =>
      assert(groupedByLabel(entry.label) eq entry.keyRef, entry.label)
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

  test("ReleaseIO source no longer defines private catalog-forwarding aliases") {
    assert(
      !releaseIOSource.contains("CorePublicKeyCatalog."),
      "Expected ReleaseIO.scala to stop forwarding catalog-backed public keys"
    )
    assert(
      !raw"private\[release\]\s+lazy val _releaseIO(?!InternalReleaseHash|InternalReleaseTag)".r
        .findFirstIn(releaseIOSource)
        .isDefined,
      "Expected only internal manifest helper keys to remain as _releaseIO private vals"
    )
  }
}
