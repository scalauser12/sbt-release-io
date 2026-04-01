package io.release.internal

import io.release.monorepo.MonorepoReleaseIO
import sbt.Setting

private[release] object MonorepoDefaultSettings {

  def commandAndHookSettings: Seq[Setting[?]] = Seq(
    MonorepoReleaseIO.releaseIOMonorepoCrossBuild                      := false,
    MonorepoReleaseIO.releaseIOMonorepoSkipTests                       := false,
    MonorepoReleaseIO.releaseIOMonorepoSkipPublish                     := false,
    MonorepoReleaseIO.releaseIOMonorepoEnableSnapshotDependenciesCheck := true,
    MonorepoReleaseIO.releaseIOMonorepoEnableRunClean                  := true,
    MonorepoReleaseIO.releaseIOMonorepoEnableRunTests                  := true,
    MonorepoReleaseIO.releaseIOMonorepoEnableTagging                   := true,
    MonorepoReleaseIO.releaseIOMonorepoEnablePublish                   := true,
    MonorepoReleaseIO.releaseIOMonorepoEnablePush                      := true,
    MonorepoReleaseIO.releaseIOMonorepoBeforeSelectionHooks            := Seq.empty,
    MonorepoReleaseIO.releaseIOMonorepoAfterSelectionHooks             := Seq.empty,
    MonorepoReleaseIO.releaseIOMonorepoBeforeVersionResolutionHooks    := Seq.empty,
    MonorepoReleaseIO.releaseIOMonorepoAfterVersionResolutionHooks     := Seq.empty,
    MonorepoReleaseIO.releaseIOMonorepoBeforeReleaseVersionWriteHooks  := Seq.empty,
    MonorepoReleaseIO.releaseIOMonorepoAfterReleaseVersionWriteHooks   := Seq.empty,
    MonorepoReleaseIO.releaseIOMonorepoBeforeReleaseCommitHooks        := Seq.empty,
    MonorepoReleaseIO.releaseIOMonorepoAfterReleaseCommitHooks         := Seq.empty,
    MonorepoReleaseIO.releaseIOMonorepoBeforeTagHooks                  := Seq.empty,
    MonorepoReleaseIO.releaseIOMonorepoAfterTagHooks                   := Seq.empty,
    MonorepoReleaseIO.releaseIOMonorepoBeforePublishHooks              := Seq.empty,
    MonorepoReleaseIO.releaseIOMonorepoAfterPublishHooks               := Seq.empty,
    MonorepoReleaseIO.releaseIOMonorepoBeforeNextVersionWriteHooks     := Seq.empty,
    MonorepoReleaseIO.releaseIOMonorepoAfterNextVersionWriteHooks      := Seq.empty,
    MonorepoReleaseIO.releaseIOMonorepoBeforeNextCommitHooks           := Seq.empty,
    MonorepoReleaseIO.releaseIOMonorepoAfterNextCommitHooks            := Seq.empty,
    MonorepoReleaseIO.releaseIOMonorepoBeforePushHooks                 := Seq.empty,
    MonorepoReleaseIO.releaseIOMonorepoAfterPushHooks                  := Seq.empty,
    MonorepoReleaseIO.releaseIOMonorepoPublishArtifactsChecks          := true,
    MonorepoReleaseIO.releaseIOMonorepoInteractive                     := false
  )
}
