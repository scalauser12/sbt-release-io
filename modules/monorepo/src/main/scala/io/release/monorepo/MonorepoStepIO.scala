package io.release.monorepo

import cats.effect.IO

/** A monorepo release step that can operate at global scope or per-project scope.
  *
  * Steps are executed in two phases by the composer:
  *  1. '''Check phase''' — validates all steps before any actions execute. Only the returned
  *     context/state is discarded; external side effects performed by checks are not rolled back.
  *     Custom checks should therefore be side-effect free and safe to run more than once.
  *  2. '''Action phase''' — runs steps sequentially, with per-project failure isolation
  *     for [[MonorepoStepIO.PerProject]] steps and global failure propagation for [[MonorepoStepIO.Global]] steps.
  *
  * @see [[MonorepoStepIO.Global]]     for steps that run once (e.g., push changes)
  * @see [[MonorepoStepIO.PerProject]] for steps that run per subproject (e.g., publish)
  * @see [[MonorepoStepIO.compose]]    for the orchestration entry point
  */
sealed trait MonorepoStepIO {
  def name: String
}

object MonorepoStepIO {

  /** A step that runs once globally (e.g., check clean working dir, push changes). */
  case class Global(
      name: String,
      action: MonorepoContext => IO[MonorepoContext],
      check: MonorepoContext => IO[MonorepoContext] = ctx => IO.pure(ctx)
  ) extends MonorepoStepIO

  /** A step that runs once per selected project in topological order
    * (e.g., set version, publish, tag).
    */
  case class PerProject(
      name: String,
      action: (MonorepoContext, ProjectReleaseInfo) => IO[MonorepoContext],
      check: (MonorepoContext, ProjectReleaseInfo) => IO[MonorepoContext] = (ctx, _) =>
        IO.pure(ctx),
      enableCrossBuild: Boolean = false
  ) extends MonorepoStepIO

  /** Compose a sequence of monorepo steps into a two-phase IO program.
    * When `crossBuild` is true, steps with `enableCrossBuild` run once per `crossScalaVersions`.
    */
  def compose(steps: Seq[MonorepoStepIO], crossBuild: Boolean = false)(
      initialCtx: MonorepoContext
  ): IO[MonorepoContext] =
    MonorepoComposer.compose(steps, crossBuild)(initialCtx)
}
