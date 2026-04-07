package io.release.internal

import cats.effect.IO
import io.release.ReleaseCtx
import io.release.ReleaseCtxOps
import io.release.ReleaseKeys
import io.release.ReleaseManifestMetadataSupport
import io.release.VcsOps
import sbt.ProjectRef
import sbt.State

private[release] object CommandRuntimeSupport {

  def cleanReleaseState(
      state: State,
      projectRefs: Seq[ProjectRef] = Nil
  ): State =
    ReleaseManifestMetadataSupport.clearReleaseManifestMetadata(
      state.remove(ReleaseKeys.versions),
      projectRefs
    )

  def preparePushIfNeeded[C <: ReleaseCtx: ReleaseCtxOps](
      ctx: C,
      stepNames: Seq[String],
      prefix: String
  ): IO[C] =
    if (stepNames.contains(VcsOps.PushChangesStepName))
      VcsOps.preparePushRelease(
        ctx,
        prefix,
        remoteCheckLog = Some(remote =>
          ctx.state.log.info(s"$prefix Checking remote [$remote] before release actions ...")
        )
      )
    else IO.pure(ctx)

}
