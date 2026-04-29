package io.release.runtime.command

import _root_.sbt.State
import io.release.ReleaseKeys
import io.release.ReleaseManifestMetadataSupport

private[release] object CommandStateSupport {

  def cleanReleaseState(state: State): State =
    ReleaseManifestMetadataSupport.clearReleaseManifestMetadata(
      state.remove(ReleaseKeys.versions)
    )
}
