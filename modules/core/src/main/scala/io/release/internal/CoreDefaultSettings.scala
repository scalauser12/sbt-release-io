package io.release.internal

import io.release.ReleaseIO
import sbt.Setting

private[release] object CoreDefaultSettings {

  def commandAndHookSettings: Seq[Setting[?]] = Seq(
    ReleaseIO.releaseIOBehaviorCrossBuild                    := false,
    ReleaseIO.releaseIOBehaviorSkipPublish                   := false,
    ReleaseIO.releaseIOBehaviorInteractive                   := false,
    ReleaseIO.releaseIODefaultsTagExistsAnswer               := None,
    ReleaseIO.releaseIODefaultsSnapshotDependenciesAnswer    := None,
    ReleaseIO.releaseIODefaultsRemoteCheckFailureAnswer      := None,
    ReleaseIO.releaseIODefaultsUpstreamBehindAnswer          := None,
    ReleaseIO.releaseIODefaultsPushAnswer                    := None,
    ReleaseIO.releaseIOPolicyEnableSnapshotDependenciesCheck := true,
    ReleaseIO.releaseIOPolicyEnableRunClean                  := true,
    ReleaseIO.releaseIOPolicyEnableRunTests                  := true,
    ReleaseIO.releaseIOPolicyEnableTagging                   := true,
    ReleaseIO.releaseIOPolicyEnablePublish                   := true,
    ReleaseIO.releaseIOPolicyEnablePush                      := true,
    ReleaseIO.releaseIOHooksAfterCleanCheck                  := Seq.empty,
    ReleaseIO.releaseIOHooksBeforeVersionResolution          := Seq.empty,
    ReleaseIO.releaseIOHooksAfterVersionResolution           := Seq.empty,
    ReleaseIO.releaseIOHooksBeforeReleaseVersionWrite        := Seq.empty,
    ReleaseIO.releaseIOHooksAfterReleaseVersionWrite         := Seq.empty,
    ReleaseIO.releaseIOHooksBeforeReleaseCommit              := Seq.empty,
    ReleaseIO.releaseIOHooksAfterReleaseCommit               := Seq.empty,
    ReleaseIO.releaseIOHooksBeforeTag                        := Seq.empty,
    ReleaseIO.releaseIOHooksAfterTag                         := Seq.empty,
    ReleaseIO.releaseIOHooksBeforePublish                    := Seq.empty,
    ReleaseIO.releaseIOHooksAfterPublish                     := Seq.empty,
    ReleaseIO.releaseIOHooksBeforeNextVersionWrite           := Seq.empty,
    ReleaseIO.releaseIOHooksAfterNextVersionWrite            := Seq.empty,
    ReleaseIO.releaseIOHooksBeforeNextCommit                 := Seq.empty,
    ReleaseIO.releaseIOHooksAfterNextCommit                  := Seq.empty,
    ReleaseIO.releaseIOHooksBeforePush                       := Seq.empty,
    ReleaseIO.releaseIOHooksAfterPush                        := Seq.empty,
    ReleaseIO.releaseIOVcsRemoteCheckTimeout                 := scala.concurrent.duration.DurationInt(60).seconds
  )
}
