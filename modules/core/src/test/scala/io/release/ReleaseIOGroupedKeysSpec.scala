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
    "releaseIOVersioningBump",
    "releaseIOVersioningReleaseVersion",
    "releaseIOVersioningNextVersion",
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

  private val compileTimeLabels = Set(
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
    keyLabel(ReleasePluginIO.autoImport.releaseIOVersioningBump),
    keyLabel(ReleasePluginIO.autoImport.releaseIOVersioningReleaseVersion),
    keyLabel(ReleasePluginIO.autoImport.releaseIOVersioningNextVersion),
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

  private lazy val reflectiveLabels = {
    val autoImport      = ReleasePluginIO.autoImport
    val autoImportClass = autoImport.getClass

    autoImportClass.getMethods.iterator
      .filter(method =>
        method.getDeclaringClass == autoImportClass &&
          method.getParameterCount == 0 &&
          (
            classOf[SettingKey[?]].isAssignableFrom(method.getReturnType) ||
              classOf[TaskKey[?]].isAssignableFrom(method.getReturnType)
          )
      )
      .map(method =>
        method.invoke(autoImport) match {
          case key: SettingKey[?] => keyLabel(key)
          case key: TaskKey[?]    => keyLabel(key)
          case other              =>
            fail(
              s"Expected ${method.getName} to return SettingKey or TaskKey, got ${other.getClass.getName}"
            )
        }
      )
      .toSet
  }

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
    "clearReleaseManifestMetadata"
  )

  private lazy val releasePluginSource =
    TestRepoFiles.readString("modules/core/src/main/scala/io/release/ReleasePluginIO.scala")

  test("ReleasePluginIO.autoImport exposes the full set of 50 expected public keys") {
    assertEquals(compileTimeLabels, expectedLabels)
    assertEquals(reflectiveLabels, expectedLabels)
    assertEquals(reflectiveLabels.size, 50)
  }

  test("grouped core settings resolve representative defaults from ReleasePluginIO.autoImport") {
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
        assertEquals(extracted.get(ReleasePluginIO.autoImport.releaseIODefaultsPushAnswer), None)
        assertEquals(extracted.get(ReleasePluginIO.autoImport.releaseIOPolicyEnablePush), true)
        assertEquals(extracted.get(ReleasePluginIO.autoImport.releaseIOHooksBeforeTag), Seq.empty)
        assertEquals(extracted.get(ReleasePluginIO.autoImport.releaseIOVersioningUseGlobal), true)
        assertEquals(
          extracted.get(ReleasePluginIO.autoImport.releaseIOVcsIgnoreUntrackedFiles),
          false
        )
        assertEquals(extracted.get(ReleasePluginIO.autoImport.releaseIOPublishChecks), true)
      }
    }
  }

  test("ReleasePluginIO source keeps autoImport limited to grouped public project keys") {
    assert(releasePluginSource.contains("object ReleasePluginIOAutoImport"))
    removedAliases.foreach { name =>
      assert(
        !s"(?m)^\\s+(?:lazy\\s+)?val ${java.util.regex.Pattern.quote(name)}\\b".r
          .findFirstIn(releasePluginSource)
          .isDefined,
        s"Expected $name to stay out of ReleasePluginIO.scala"
      )
    }
    autoImportNonProjectMembers.foreach { name =>
      assert(
        !releasePluginSource.contains(name),
        s"Expected $name to stay out of ReleasePluginIO.scala autoImport keys"
      )
    }
    assert(!releasePluginSource.contains("extends AutoPlugin with ReleaseIO"))
    assert(!releasePluginSource.contains("[[ReleaseIO]]"))
  }
}
