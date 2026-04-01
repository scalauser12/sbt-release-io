package io.release.internal

import io.release.ReleaseIO
import sbt.Setting

private[release] object CoreDefaultSettings {

  def commandAndHookSettings: Seq[Setting[?]] = Seq(
    ReleaseIO.releaseIOCrossBuild                        := false,
    ReleaseIO.releaseIOSkipPublish                       := false,
    ReleaseIO.releaseIOInteractive                       := false,
    ReleaseIO.releaseIODefaultTagExistsAnswer            := None,
    ReleaseIO.releaseIODefaultSnapshotDependenciesAnswer := None,
    ReleaseIO.releaseIODefaultRemoteCheckFailureAnswer   := None,
    ReleaseIO.releaseIODefaultUpstreamBehindAnswer       := None,
    ReleaseIO.releaseIODefaultPushAnswer                 := None,
    ReleaseIO.releaseIOEnableSnapshotDependenciesCheck   := true,
    ReleaseIO.releaseIOEnableRunClean                    := true,
    ReleaseIO.releaseIOEnableRunTests                    := true,
    ReleaseIO.releaseIOEnableTagging                     := true,
    ReleaseIO.releaseIOEnablePublish                     := true,
    ReleaseIO.releaseIOEnablePush                        := true,
    ReleaseIO.releaseIOAfterCleanCheckHooks              := Seq.empty,
    ReleaseIO.releaseIOBeforeVersionResolutionHooks      := Seq.empty,
    ReleaseIO.releaseIOAfterVersionResolutionHooks       := Seq.empty,
    ReleaseIO.releaseIOBeforeReleaseVersionWriteHooks    := Seq.empty,
    ReleaseIO.releaseIOAfterReleaseVersionWriteHooks     := Seq.empty,
    ReleaseIO.releaseIOBeforeReleaseCommitHooks          := Seq.empty,
    ReleaseIO.releaseIOAfterReleaseCommitHooks           := Seq.empty,
    ReleaseIO.releaseIOBeforeTagHooks                    := Seq.empty,
    ReleaseIO.releaseIOAfterTagHooks                     := Seq.empty,
    ReleaseIO.releaseIOBeforePublishHooks                := Seq.empty,
    ReleaseIO.releaseIOAfterPublishHooks                 := Seq.empty,
    ReleaseIO.releaseIOBeforeNextVersionWriteHooks       := Seq.empty,
    ReleaseIO.releaseIOAfterNextVersionWriteHooks        := Seq.empty,
    ReleaseIO.releaseIOBeforeNextCommitHooks             := Seq.empty,
    ReleaseIO.releaseIOAfterNextCommitHooks              := Seq.empty,
    ReleaseIO.releaseIOBeforePushHooks                   := Seq.empty,
    ReleaseIO.releaseIOAfterPushHooks                    := Seq.empty,
    ReleaseIO.releaseIOVcsRemoteCheckTimeout             := scala.concurrent.duration.DurationInt(60).seconds
  )
}
