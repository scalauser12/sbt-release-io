package io.release

import cats.effect.IO
import io.release.vcs.{Vcs => ReleaseVcs}
import sbt.State

/** Context threaded through each release step. */
case class ReleaseContext(
    state: State,
    versions: Option[(String, String)] = None, // (releaseVersion, nextVersion)
    vcs: Option[ReleaseVcs] = None,
    skipTests: Boolean = false,
    skipPublish: Boolean = false,
    attributes: Map[String, String] = Map.empty
) {
  def withVersions(release: String, next: String): ReleaseContext =
    copy(versions = Some((release, next)))

  def withVcs(v: ReleaseVcs): ReleaseContext =
    copy(vcs = Some(v))

  def attr(key: String): Option[String] = attributes.get(key)

  def withAttr(key: String, value: String): ReleaseContext =
    copy(attributes = attributes + (key -> value))
}

/** A single release step: a function from ReleaseContext to IO[ReleaseContext]. */
case class ReleaseStepIO(
    name: String,
    action: ReleaseContext => IO[ReleaseContext]
) {
  def map(f: ReleaseContext => ReleaseContext): ReleaseStepIO =
    copy(action = ctx => action(ctx).map(f))

  def flatMap(f: ReleaseContext => IO[ReleaseContext]): ReleaseStepIO =
    copy(action = ctx => action(ctx).flatMap(f))
}

object ReleaseStepIO {

  /** Create a step that transforms the context purely. */
  def pure(name: String)(f: ReleaseContext => ReleaseContext): ReleaseStepIO =
    ReleaseStepIO(name, ctx => IO(f(ctx)))

  /** Create a step from a side-effecting function. */
  def io(name: String)(f: ReleaseContext => IO[ReleaseContext]): ReleaseStepIO =
    new ReleaseStepIO(name, f)

  /** Compose a sequence of steps into a single IO program. */
  def compose(steps: Seq[ReleaseStepIO]): ReleaseContext => IO[ReleaseContext] =
    ctx =>
      steps.foldLeft(IO.pure(ctx)) { (ioCtx, step) =>
        ioCtx.flatMap { c =>
          IO(println(s"[release-io] Executing step: ${step.name}")) *>
            step.action(c)
        }
      }
}
