package io.release.runtime.command

import _root_.sbt.ProjectRef
import _root_.sbt.State
import io.release.ReleaseKeys
import io.release.ReleaseManifestMetadataSupport

private[release] object CommandStateSupport {

  def cleanReleaseState(
      state: State,
      projectRefs: Seq[ProjectRef] = Nil
  ): State =
    ReleaseManifestMetadataSupport.clearReleaseManifestMetadata(
      state.remove(ReleaseKeys.versions),
      projectRefs
    )
}
