package io.release

import cats.effect.IO
import munit.CatsEffectSuite
import sbt.SettingKey
import sbt.TaskKey

import scala.annotation.nowarn

class ReleaseIOGroupedKeysSpec extends CatsEffectSuite with ReleasePluginIOSpecSupport {

  private def keyLabel[A](key: SettingKey[A]): String = key.key.label
  private def keyLabel[A](key: TaskKey[A]): String    = key.key.label

  @nowarn("cat=deprecation")
  private object CompatibilityMixin extends ReleaseIO

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
    keyLabel(ReleasePluginIO.autoImport.releaseIOBehaviorCrossBuild),
    keyLabel(ReleasePluginIO.autoImport.releaseIOBehaviorSkipPublish),
    keyLabel(ReleasePluginIO.autoImport.releaseIOBehaviorInteractive),
    keyLabel(ReleasePluginIO.autoImport.releaseIODefaultsTagExistsAnswer),
    keyLabel(ReleasePluginIO.autoImport.releaseIODefaultsSnapshotDependenciesAnswer),
    keyLabel(ReleasePluginIO.autoImport.releaseIODefaultsRemoteCheckFailureAnswer),
    keyLabel(ReleasePluginIO.autoImport.releaseIODefaultsUpstreamBehindAnswer),
    keyLabel(ReleasePluginIO.autoImport.releaseIODefaultsPushAnswer),
    keyLabel(ReleasePluginIO.autoImport.releaseIOPolicyEnableSnapshotDependenciesCheck),
    keyLabel(ReleasePluginIO.autoImport.releaseIOPolicyEnableRunClean),
    keyLabel(ReleasePluginIO.autoImport.releaseIOPolicyEnableRunTests),
    keyLabel(ReleasePluginIO.autoImport.releaseIOPolicyEnableTagging),
    keyLabel(ReleasePluginIO.autoImport.releaseIOPolicyEnablePublish),
    keyLabel(ReleasePluginIO.autoImport.releaseIOPolicyEnablePush),
    keyLabel(ReleasePluginIO.autoImport.releaseIOHooksAfterCleanCheck),
    keyLabel(ReleasePluginIO.autoImport.releaseIOHooksBeforeVersionResolution),
    keyLabel(ReleasePluginIO.autoImport.releaseIOHooksAfterVersionResolution),
    keyLabel(ReleasePluginIO.autoImport.releaseIOHooksBeforeReleaseVersionWrite),
    keyLabel(ReleasePluginIO.autoImport.releaseIOHooksAfterReleaseVersionWrite),
    keyLabel(ReleasePluginIO.autoImport.releaseIOHooksBeforeReleaseCommit),
    keyLabel(ReleasePluginIO.autoImport.releaseIOHooksAfterReleaseCommit),
    keyLabel(ReleasePluginIO.autoImport.releaseIOHooksBeforeTag),
    keyLabel(ReleasePluginIO.autoImport.releaseIOHooksAfterTag),
    keyLabel(ReleasePluginIO.autoImport.releaseIOHooksBeforePublish),
    keyLabel(ReleasePluginIO.autoImport.releaseIOHooksAfterPublish),
    keyLabel(ReleasePluginIO.autoImport.releaseIOHooksBeforeNextVersionWrite),
    keyLabel(ReleasePluginIO.autoImport.releaseIOHooksAfterNextVersionWrite),
    keyLabel(ReleasePluginIO.autoImport.releaseIOHooksBeforeNextCommit),
    keyLabel(ReleasePluginIO.autoImport.releaseIOHooksAfterNextCommit),
    keyLabel(ReleasePluginIO.autoImport.releaseIOHooksBeforePush),
    keyLabel(ReleasePluginIO.autoImport.releaseIOHooksAfterPush),
    keyLabel(ReleasePluginIO.autoImport.releaseIOVersioningReadVersion),
    keyLabel(ReleasePluginIO.autoImport.releaseIOVersioningFileContents),
    keyLabel(ReleasePluginIO.autoImport.releaseIOVersioningFile),
    keyLabel(ReleasePluginIO.autoImport.releaseIOVersioningUseGlobal),
    keyLabel(ReleasePluginIO.autoImport.releaseIOVersioningReleaseVersion),
    keyLabel(ReleasePluginIO.autoImport.releaseIOVersioningNextVersion),
    keyLabel(ReleasePluginIO.autoImport.releaseIOVersioningBump),
    keyLabel(ReleasePluginIO.autoImport.releaseIOVcsSign),
    keyLabel(ReleasePluginIO.autoImport.releaseIOVcsSignOff),
    keyLabel(ReleasePluginIO.autoImport.releaseIOVcsIgnoreUntrackedFiles),
    keyLabel(ReleasePluginIO.autoImport.releaseIOVcsRemoteCheckTimeout),
    keyLabel(ReleasePluginIO.autoImport.releaseIOVcsTagName),
    keyLabel(ReleasePluginIO.autoImport.releaseIOVcsTagComment),
    keyLabel(ReleasePluginIO.autoImport.releaseIOVcsReleaseCommitMessage),
    keyLabel(ReleasePluginIO.autoImport.releaseIOVcsNextCommitMessage),
    keyLabel(ReleasePluginIO.autoImport.releaseIOPublishAction),
    keyLabel(ReleasePluginIO.autoImport.releaseIOPublishChecks),
    keyLabel(ReleasePluginIO.autoImport.releaseIORuntimeCurrentVersion),
    keyLabel(ReleasePluginIO.autoImport.releaseIODiagnosticsSnapshotDependencies)
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

  private val autoImportNonProjectMembers = Seq(
    "releaseIOInternalReleaseHash",
    "releaseIOInternalReleaseTag",
    "releaseManifestPackageOptions",
    "releaseManifestHashSettings",
    "releaseManifestTagSettings",
    "existingReleaseManifestSettings",
    "clearReleaseManifestMetadata"
  )

  private lazy val releasePluginSource =
    TestRepoFiles.readString("modules/core/src/main/scala/io/release/ReleasePluginIO.scala")

  private lazy val releaseIOSource =
    TestRepoFiles.readString("modules/core/src/main/scala/io/release/ReleaseIO.scala")

  @nowarn("cat=deprecation")
  private def compatibilityLabels: Set[String] = Set(
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

  @nowarn("cat=deprecation")
  private def compatibilityRefs: Seq[(AnyRef, AnyRef)] = Seq(
    ReleaseIO.releaseIOBehaviorCrossBuild                    -> ReleasePluginIO.autoImport.releaseIOBehaviorCrossBuild,
    ReleaseIO.releaseIOBehaviorSkipPublish                   -> ReleasePluginIO.autoImport.releaseIOBehaviorSkipPublish,
    ReleaseIO.releaseIOBehaviorInteractive                   -> ReleasePluginIO.autoImport.releaseIOBehaviorInteractive,
    ReleaseIO.releaseIODefaultsTagExistsAnswer               -> ReleasePluginIO.autoImport.releaseIODefaultsTagExistsAnswer,
    ReleaseIO.releaseIODefaultsSnapshotDependenciesAnswer    -> ReleasePluginIO.autoImport.releaseIODefaultsSnapshotDependenciesAnswer,
    ReleaseIO.releaseIODefaultsRemoteCheckFailureAnswer      -> ReleasePluginIO.autoImport.releaseIODefaultsRemoteCheckFailureAnswer,
    ReleaseIO.releaseIODefaultsUpstreamBehindAnswer          -> ReleasePluginIO.autoImport.releaseIODefaultsUpstreamBehindAnswer,
    ReleaseIO.releaseIODefaultsPushAnswer                    -> ReleasePluginIO.autoImport.releaseIODefaultsPushAnswer,
    ReleaseIO.releaseIOPolicyEnableSnapshotDependenciesCheck -> ReleasePluginIO.autoImport.releaseIOPolicyEnableSnapshotDependenciesCheck,
    ReleaseIO.releaseIOPolicyEnableRunClean                  -> ReleasePluginIO.autoImport.releaseIOPolicyEnableRunClean,
    ReleaseIO.releaseIOPolicyEnableRunTests                  -> ReleasePluginIO.autoImport.releaseIOPolicyEnableRunTests,
    ReleaseIO.releaseIOPolicyEnableTagging                   -> ReleasePluginIO.autoImport.releaseIOPolicyEnableTagging,
    ReleaseIO.releaseIOPolicyEnablePublish                   -> ReleasePluginIO.autoImport.releaseIOPolicyEnablePublish,
    ReleaseIO.releaseIOPolicyEnablePush                      -> ReleasePluginIO.autoImport.releaseIOPolicyEnablePush,
    ReleaseIO.releaseIOHooksAfterCleanCheck                  -> ReleasePluginIO.autoImport.releaseIOHooksAfterCleanCheck,
    ReleaseIO.releaseIOHooksBeforeVersionResolution          -> ReleasePluginIO.autoImport.releaseIOHooksBeforeVersionResolution,
    ReleaseIO.releaseIOHooksAfterVersionResolution           -> ReleasePluginIO.autoImport.releaseIOHooksAfterVersionResolution,
    ReleaseIO.releaseIOHooksBeforeReleaseVersionWrite        -> ReleasePluginIO.autoImport.releaseIOHooksBeforeReleaseVersionWrite,
    ReleaseIO.releaseIOHooksAfterReleaseVersionWrite         -> ReleasePluginIO.autoImport.releaseIOHooksAfterReleaseVersionWrite,
    ReleaseIO.releaseIOHooksBeforeReleaseCommit              -> ReleasePluginIO.autoImport.releaseIOHooksBeforeReleaseCommit,
    ReleaseIO.releaseIOHooksAfterReleaseCommit               -> ReleasePluginIO.autoImport.releaseIOHooksAfterReleaseCommit,
    ReleaseIO.releaseIOHooksBeforeTag                        -> ReleasePluginIO.autoImport.releaseIOHooksBeforeTag,
    ReleaseIO.releaseIOHooksAfterTag                         -> ReleasePluginIO.autoImport.releaseIOHooksAfterTag,
    ReleaseIO.releaseIOHooksBeforePublish                    -> ReleasePluginIO.autoImport.releaseIOHooksBeforePublish,
    ReleaseIO.releaseIOHooksAfterPublish                     -> ReleasePluginIO.autoImport.releaseIOHooksAfterPublish,
    ReleaseIO.releaseIOHooksBeforeNextVersionWrite           -> ReleasePluginIO.autoImport.releaseIOHooksBeforeNextVersionWrite,
    ReleaseIO.releaseIOHooksAfterNextVersionWrite            -> ReleasePluginIO.autoImport.releaseIOHooksAfterNextVersionWrite,
    ReleaseIO.releaseIOHooksBeforeNextCommit                 -> ReleasePluginIO.autoImport.releaseIOHooksBeforeNextCommit,
    ReleaseIO.releaseIOHooksAfterNextCommit                  -> ReleasePluginIO.autoImport.releaseIOHooksAfterNextCommit,
    ReleaseIO.releaseIOHooksBeforePush                       -> ReleasePluginIO.autoImport.releaseIOHooksBeforePush,
    ReleaseIO.releaseIOHooksAfterPush                        -> ReleasePluginIO.autoImport.releaseIOHooksAfterPush,
    ReleaseIO.releaseIOVersioningReadVersion                 -> ReleasePluginIO.autoImport.releaseIOVersioningReadVersion,
    ReleaseIO.releaseIOVersioningFileContents                -> ReleasePluginIO.autoImport.releaseIOVersioningFileContents,
    ReleaseIO.releaseIOVersioningFile                        -> ReleasePluginIO.autoImport.releaseIOVersioningFile,
    ReleaseIO.releaseIOVersioningUseGlobal                   -> ReleasePluginIO.autoImport.releaseIOVersioningUseGlobal,
    ReleaseIO.releaseIOVersioningReleaseVersion              -> ReleasePluginIO.autoImport.releaseIOVersioningReleaseVersion,
    ReleaseIO.releaseIOVersioningNextVersion                 -> ReleasePluginIO.autoImport.releaseIOVersioningNextVersion,
    ReleaseIO.releaseIOVersioningBump                        -> ReleasePluginIO.autoImport.releaseIOVersioningBump,
    ReleaseIO.releaseIOVcsSign                               -> ReleasePluginIO.autoImport.releaseIOVcsSign,
    ReleaseIO.releaseIOVcsSignOff                            -> ReleasePluginIO.autoImport.releaseIOVcsSignOff,
    ReleaseIO.releaseIOVcsIgnoreUntrackedFiles               -> ReleasePluginIO.autoImport.releaseIOVcsIgnoreUntrackedFiles,
    ReleaseIO.releaseIOVcsRemoteCheckTimeout                 -> ReleasePluginIO.autoImport.releaseIOVcsRemoteCheckTimeout,
    ReleaseIO.releaseIOVcsTagName                            -> ReleasePluginIO.autoImport.releaseIOVcsTagName,
    ReleaseIO.releaseIOVcsTagComment                         -> ReleasePluginIO.autoImport.releaseIOVcsTagComment,
    ReleaseIO.releaseIOVcsReleaseCommitMessage               -> ReleasePluginIO.autoImport.releaseIOVcsReleaseCommitMessage,
    ReleaseIO.releaseIOVcsNextCommitMessage                  -> ReleasePluginIO.autoImport.releaseIOVcsNextCommitMessage,
    ReleaseIO.releaseIOPublishAction                         -> ReleasePluginIO.autoImport.releaseIOPublishAction,
    ReleaseIO.releaseIOPublishChecks                         -> ReleasePluginIO.autoImport.releaseIOPublishChecks,
    ReleaseIO.releaseIORuntimeCurrentVersion                 -> ReleasePluginIO.autoImport.releaseIORuntimeCurrentVersion,
    ReleaseIO.releaseIODiagnosticsSnapshotDependencies       -> ReleasePluginIO.autoImport.releaseIODiagnosticsSnapshotDependencies
  )

  test("ReleasePluginIO.autoImport exposes the full set of 50 expected public keys") {
    assertEquals(actualLabels, expectedLabels)
    assertEquals(actualLabels.size, 50)
  }

  test("grouped core settings resolve expected defaults from ReleasePluginIO.autoImport") {
    stateResource("release-io-grouped-keys", HookFriendlyPlugin).use { loaded =>
      IO {
        val extracted = TestkitSbtCompat.extract(loaded.state)

        assertEquals(extracted.get(ReleasePluginIO.autoImport.releaseIOBehaviorCrossBuild), false)
        assertEquals(extracted.get(ReleasePluginIO.autoImport.releaseIOBehaviorSkipPublish), false)
        assertEquals(extracted.get(ReleasePluginIO.autoImport.releaseIOBehaviorInteractive), false)
        assertEquals(
          extracted.get(ReleasePluginIO.autoImport.releaseIODefaultsTagExistsAnswer),
          None
        )
        assertEquals(extracted.get(ReleasePluginIO.autoImport.releaseIOPolicyEnablePush), true)
        assertEquals(extracted.get(ReleasePluginIO.autoImport.releaseIOHooksBeforeTag), Seq.empty)
      }
    }
  }

  test("ReleasePluginIO source keeps autoImport limited to public project keys") {
    assert(releasePluginSource.contains("object ReleasePluginIOAutoImport"))
    autoImportNonProjectMembers.foreach { name =>
      assert(
        !releasePluginSource.contains(name),
        s"Expected $name to stay out of ReleasePluginIO.scala autoImport keys"
      )
    }
  }

  test("ReleaseIO compatibility namespace forwards the canonical public keys") {
    assertEquals(compatibilityLabels, expectedLabels)
    compatibilityRefs.foreach { case (legacy, canonical) =>
      assert(legacy eq canonical)
    }
  }

  test("ReleaseIO remains usable as a deprecated mixin") {
    assertEquals(
      keyLabel(CompatibilityMixin.releaseIOBehaviorCrossBuild),
      "releaseIOBehaviorCrossBuild"
    )
    assert(
      CompatibilityMixin.releaseIOHooksBeforeTag eq ReleasePluginIO.autoImport.releaseIOHooksBeforeTag
    )
    assert(
      CompatibilityMixin.releaseIOVersioningBump eq ReleasePluginIO.autoImport.releaseIOVersioningBump
    )
  }

  test("ReleaseIO source is a deprecated compatibility mixin namespace without removed aliases") {
    assert(releaseIOSource.contains("""@deprecated("Use ReleasePluginIO.autoImport instead.""""))
    assert(releaseIOSource.contains("trait ReleaseIO"))
    assert(releaseIOSource.contains("object ReleaseIO extends ReleaseIO"))
    removedAliases.foreach { name =>
      assert(
        !s"(?m)^\\s+(?:lazy\\s+)?val ${java.util.regex.Pattern.quote(name)}\\b".r
          .findFirstIn(releaseIOSource)
          .isDefined,
        s"Expected $name to be removed from ReleaseIO.scala"
      )
    }
    autoImportNonProjectMembers.foreach { name =>
      assert(
        !releaseIOSource.contains(name),
        s"Expected $name to stay out of ReleaseIO.scala"
      )
    }
  }
}
