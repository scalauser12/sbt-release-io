package io.release.internal

import io.release.monorepo.MonorepoReleaseIO
import sbt.Setting

private[release] object MonorepoDefaultSettings {

  def commandAndHookSettings: Seq[Setting[?]] = Seq(
    MonorepoReleaseIO.releaseIOMonorepoBehaviorCrossBuild                    := false,
    MonorepoReleaseIO.releaseIOMonorepoBehaviorSkipTests                     := false,
    MonorepoReleaseIO.releaseIOMonorepoBehaviorSkipPublish                   := false,
    MonorepoReleaseIO.releaseIOMonorepoPolicyEnableSnapshotDependenciesCheck := true,
    MonorepoReleaseIO.releaseIOMonorepoPolicyEnableRunClean                  := true,
    MonorepoReleaseIO.releaseIOMonorepoPolicyEnableRunTests                  := true,
    MonorepoReleaseIO.releaseIOMonorepoPolicyEnableTagging                   := true,
    MonorepoReleaseIO.releaseIOMonorepoPolicyEnablePublish                   := true,
    MonorepoReleaseIO.releaseIOMonorepoPolicyEnablePush                      := true,
    MonorepoReleaseIO.releaseIOMonorepoHooksBeforeSelection                  := Seq.empty,
    MonorepoReleaseIO.releaseIOMonorepoHooksAfterSelection                   := Seq.empty,
    MonorepoReleaseIO.releaseIOMonorepoHooksBeforeVersionResolution          := Seq.empty,
    MonorepoReleaseIO.releaseIOMonorepoHooksAfterVersionResolution           := Seq.empty,
    MonorepoReleaseIO.releaseIOMonorepoHooksBeforeReleaseVersionWrite        := Seq.empty,
    MonorepoReleaseIO.releaseIOMonorepoHooksAfterReleaseVersionWrite         := Seq.empty,
    MonorepoReleaseIO.releaseIOMonorepoHooksBeforeReleaseCommit              := Seq.empty,
    MonorepoReleaseIO.releaseIOMonorepoHooksAfterReleaseCommit               := Seq.empty,
    MonorepoReleaseIO.releaseIOMonorepoHooksBeforeTag                        := Seq.empty,
    MonorepoReleaseIO.releaseIOMonorepoHooksAfterTag                         := Seq.empty,
    MonorepoReleaseIO.releaseIOMonorepoHooksBeforePublish                    := Seq.empty,
    MonorepoReleaseIO.releaseIOMonorepoHooksAfterPublish                     := Seq.empty,
    MonorepoReleaseIO.releaseIOMonorepoHooksBeforeNextVersionWrite           := Seq.empty,
    MonorepoReleaseIO.releaseIOMonorepoHooksAfterNextVersionWrite            := Seq.empty,
    MonorepoReleaseIO.releaseIOMonorepoHooksBeforeNextCommit                 := Seq.empty,
    MonorepoReleaseIO.releaseIOMonorepoHooksAfterNextCommit                  := Seq.empty,
    MonorepoReleaseIO.releaseIOMonorepoHooksBeforePush                       := Seq.empty,
    MonorepoReleaseIO.releaseIOMonorepoHooksAfterPush                        := Seq.empty,
    MonorepoReleaseIO.releaseIOMonorepoPublishChecks                         := true,
    MonorepoReleaseIO.releaseIOMonorepoBehaviorInteractive                   := false
  )
}
