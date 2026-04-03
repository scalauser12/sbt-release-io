package io.release.internal

import io.release.monorepo.MonorepoLifecycle
import io.release.monorepo.MonorepoReleaseIO
import sbt.Setting

private[release] object MonorepoDefaultSettings {

  def commandAndHookSettings: Seq[Setting[?]] = Seq(
    MonorepoReleaseIO.releaseIOMonorepoBehaviorCrossBuild  := false,
    MonorepoReleaseIO.releaseIOMonorepoBehaviorSkipTests   := false,
    MonorepoReleaseIO.releaseIOMonorepoBehaviorSkipPublish := false
  ) ++ MonorepoLifecycle.configDefaultSettings ++ Seq(
    MonorepoReleaseIO.releaseIOMonorepoPublishChecks       := true,
    MonorepoReleaseIO.releaseIOMonorepoBehaviorInteractive := false
  )
}
