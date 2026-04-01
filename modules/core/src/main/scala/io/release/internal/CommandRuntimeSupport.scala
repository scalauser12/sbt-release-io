package io.release.internal

import cats.effect.IO
import io.release.ReleaseCtx
import io.release.ReleaseIO
import io.release.ReleaseKeys
import io.release.VcsOps
import sbt.ProjectRef
import sbt.State

private[release] object CommandRuntimeSupport {

  def cleanReleaseState(
      state: State,
      projectRefs: Seq[ProjectRef] = Nil
  ): State =
    ReleaseIO.clearReleaseManifestMetadata(state.remove(ReleaseKeys.versions), projectRefs)

  def preparePushIfNeeded[C <: ReleaseCtx[C]](
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

  def mergeMaterializedHooks[T, Config, ResourceHooks](
      plainHooks: Config,
      resourceHooks: ResourceHooks,
      maybeResource: Option[T]
  )(
      materialize: (ResourceHooks, Option[T]) => Config,
      merge: (Config, Config) => Config
  ): Config =
    merge(plainHooks, materialize(resourceHooks, maybeResource))

  def logLines(
      state: State,
      prefix: String,
      lines: Seq[String]
  ): IO[Unit] =
    ReleaseCommandRunner.logLines(state, prefix, lines)
}
