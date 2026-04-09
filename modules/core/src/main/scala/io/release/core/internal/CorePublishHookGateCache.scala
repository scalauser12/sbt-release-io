package io.release.core.internal

import cats.effect.IO
import io.release.ReleaseContext
import io.release.runtime.sbt.SbtRuntime
import sbt.Keys.scalaVersion
import sbt.{internal as _, *}

/** Freeze publish-hook gate decisions during validation so execution sees the same answer
  * even if earlier steps mutate the release state before the action phase begins.
  */
private[release] object CorePublishHookGateCache {

  final case class HookToken(
      phase: String,
      hookIndex: Int
  )

  private final case class DecisionKey(
      token: HookToken,
      scalaVersion: String
  )

  private val decisionsKey: AttributeKey[Map[DecisionKey, Boolean]] =
    AttributeKey[Map[DecisionKey, Boolean]]("releaseIOInternalCorePublishHookGateDecisions")

  def snapshotDecision(
      ctx: ReleaseContext,
      token: HookToken,
      gate: ReleaseContext => IO[Boolean]
  ): IO[(ReleaseContext, Boolean)] =
    for {
      shouldRun <- gate(ctx)
      key       <- currentKey(ctx, token)
    } yield {
      val updatedDecisions = ctx.metadata(decisionsKey).getOrElse(Map.empty).updated(key, shouldRun)
      (ctx.withMetadata(decisionsKey, updatedDecisions), shouldRun)
    }

  def resolveDecision(
      ctx: ReleaseContext,
      token: HookToken,
      fallback: => IO[Boolean]
  ): IO[Boolean] =
    currentKey(ctx, token).flatMap(key =>
      ctx.metadata(decisionsKey).flatMap(_.get(key)) match {
        case Some(shouldRun) => IO.pure(shouldRun)
        case None            => fallback
      }
    )

  private def currentKey(
      ctx: ReleaseContext,
      token: HookToken
  ): IO[DecisionKey] =
    IO.blocking {
      val extracted     = SbtRuntime.extracted(ctx.state)
      val activeVersion = extracted.getOpt(scalaVersion).getOrElse("<undefined>")
      DecisionKey(token, activeVersion)
    }
}
