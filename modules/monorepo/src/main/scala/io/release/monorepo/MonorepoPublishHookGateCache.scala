package io.release.monorepo

import cats.effect.IO
import io.release.internal.SbtRuntime
import sbt.Keys.scalaVersion
import sbt.{internal as _, *}

/** Freeze publish-hook gate decisions during validation so execution sees the same answer
  * even if earlier steps mutate the release state before the action phase begins.
  */
private[monorepo] object MonorepoPublishHookGateCache {

  final case class HookToken(
      phase: String,
      hookIndex: Int
  )

  private final case class DecisionKey(
      token: HookToken,
      projectRef: ProjectRef,
      scalaVersion: String
  )

  private val decisionsKey: AttributeKey[Map[DecisionKey, Boolean]] =
    AttributeKey[Map[DecisionKey, Boolean]]("releaseIOInternalMonorepoPublishHookGateDecisions")

  def snapshotDecision(
      ctx: MonorepoContext,
      token: HookToken,
      project: ProjectReleaseInfo,
      gate: (MonorepoContext, ProjectReleaseInfo) => IO[Boolean]
  ): IO[(MonorepoContext, Boolean)] =
    for {
      shouldRun <- gate(ctx, project)
      key       <- currentKey(ctx, token, project)
    } yield {
      val updatedDecisions = ctx.metadata(decisionsKey).getOrElse(Map.empty).updated(key, shouldRun)
      (ctx.withMetadata(decisionsKey, updatedDecisions), shouldRun)
    }

  def resolveDecision(
      ctx: MonorepoContext,
      token: HookToken,
      project: ProjectReleaseInfo,
      fallback: => IO[Boolean]
  ): IO[Boolean] =
    currentKey(ctx, token, project).flatMap(key =>
      ctx.metadata(decisionsKey).flatMap(_.get(key)) match {
        case Some(shouldRun) => IO.pure(shouldRun)
        case None            => fallback
      }
    )

  private def currentKey(
      ctx: MonorepoContext,
      token: HookToken,
      project: ProjectReleaseInfo
  ): IO[DecisionKey] =
    IO.blocking {
      val extracted     = SbtRuntime.extracted(ctx.state)
      val activeVersion =
        (project.ref / scalaVersion)
          .get(extracted.structure.data)
          .orElse(extracted.getOpt(scalaVersion))
          .getOrElse("<undefined>")
      DecisionKey(token, project.ref, activeVersion)
    }
}
