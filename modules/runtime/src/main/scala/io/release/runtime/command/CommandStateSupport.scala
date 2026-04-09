package io.release.runtime.command

import io.release.ReleaseKeys
import io.release.ReleaseManifestMetadataSupport
import _root_.sbt.ProjectRef
import _root_.sbt.State

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
