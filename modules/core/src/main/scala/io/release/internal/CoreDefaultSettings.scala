package io.release.internal

import io.release.ReleaseIO
import sbt.Setting

private[release] object CoreDefaultSettings {

  def commandAndHookSettings: Seq[Setting[?]] = Seq(
    ReleaseIO.releaseIOBehaviorCrossBuild                 := false,
    ReleaseIO.releaseIOBehaviorSkipPublish                := false,
    ReleaseIO.releaseIOBehaviorInteractive                := false,
    ReleaseIO.releaseIODefaultsTagExistsAnswer            := None,
    ReleaseIO.releaseIODefaultsSnapshotDependenciesAnswer := None,
    ReleaseIO.releaseIODefaultsRemoteCheckFailureAnswer   := None,
    ReleaseIO.releaseIODefaultsUpstreamBehindAnswer       := None,
    ReleaseIO.releaseIODefaultsPushAnswer                 := None
  ) ++ CoreLifecycle.configDefaultSettings ++ Seq(
    ReleaseIO.releaseIOVcsRemoteCheckTimeout := scala.concurrent.duration.DurationInt(60).seconds
  )
}
