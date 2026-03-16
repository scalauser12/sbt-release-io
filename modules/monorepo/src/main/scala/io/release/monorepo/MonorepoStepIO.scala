package io.release.monorepo

import cats.effect.IO

/** A monorepo release step that can operate at global scope or per-project scope.
  *
  * Steps are executed in two phases by the composer:
  *  1. '''Validation phase''' — validates steps before execution. Validation may fail the
  *     release, but it does not thread updated context through the phase.
  *  2. '''Execution phase''' — runs steps sequentially, with per-project failure isolation
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
      execute: MonorepoContext => IO[MonorepoContext],
      validate: MonorepoContext => IO[Unit] = _ => IO.unit,
      isSelectionBoundary: Boolean = false
  ) extends MonorepoStepIO

  /** A step that runs once per selected project in topological order
    * (e.g., set version, publish, tag).
    */
  case class PerProject(
      name: String,
      execute: (MonorepoContext, ProjectReleaseInfo) => IO[MonorepoContext],
      validate: (MonorepoContext, ProjectReleaseInfo) => IO[Unit] = (_, _) => IO.unit,
      enableCrossBuild: Boolean = false
  ) extends MonorepoStepIO

  // ── Builder API ──────────────────────────────────────────────────────

  /** Start building a global step. */
  def global(name: String): GlobalBuilder = new GlobalBuilder(name, _ => IO.unit, false)

  /** Start building a per-project step. */
  def perProject(name: String): PerProjectBuilder =
    new PerProjectBuilder(name, (_, _) => IO.unit, false)

  /** Start building a resource-aware global step. */
  def globalResource[T](name: String): ResourceGlobalBuilder[T] =
    new ResourceGlobalBuilder[T](name, _ => _ => IO.unit, false)

  /** Start building a resource-aware per-project step. */
  def perProjectResource[T](name: String): ResourcePerProjectBuilder[T] =
    new ResourcePerProjectBuilder[T](name, _ => (_, _) => IO.unit, false)

  /** Fluent builder for global steps. */
  final class GlobalBuilder private[MonorepoStepIO] (
      private val name: String,
      private val validateFn: MonorepoContext => IO[Unit],
      private val selectionBoundary: Boolean
  ) {

    def withValidation(f: MonorepoContext => IO[Unit]): GlobalBuilder =
      new GlobalBuilder(name, f, selectionBoundary)

    def withSelectionBoundary: GlobalBuilder =
      new GlobalBuilder(name, validateFn, true)

    def execute(f: MonorepoContext => IO[MonorepoContext]): Global =
      Global(name, f, validateFn, selectionBoundary)

    def executeAction(f: MonorepoContext => IO[Unit]): Global =
      Global(name, ctx => f(ctx).as(ctx), validateFn, selectionBoundary)

    def validateOnly: Global =
      Global(name, ctx => IO.pure(ctx), validateFn, selectionBoundary)
  }

  /** Fluent builder for per-project steps. */
  final class PerProjectBuilder private[MonorepoStepIO] (
      private val name: String,
      private val validateFn: (MonorepoContext, ProjectReleaseInfo) => IO[Unit],
      private val crossBuild: Boolean
  ) {

    def withCrossBuild: PerProjectBuilder =
      new PerProjectBuilder(name, validateFn, true)

    def withValidation(
        f: (MonorepoContext, ProjectReleaseInfo) => IO[Unit]
    ): PerProjectBuilder =
      new PerProjectBuilder(name, f, crossBuild)

    def execute(
        f: (MonorepoContext, ProjectReleaseInfo) => IO[MonorepoContext]
    ): PerProject =
      PerProject(name, f, validateFn, crossBuild)

    def executeAction(
        f: (MonorepoContext, ProjectReleaseInfo) => IO[Unit]
    ): PerProject =
      PerProject(name, (ctx, proj) => f(ctx, proj).as(ctx), validateFn, crossBuild)

    def validateOnly: PerProject =
      PerProject(name, (ctx, _) => IO.pure(ctx), validateFn, crossBuild)
  }

  /** Fluent builder for resource-aware global steps. */
  final class ResourceGlobalBuilder[T] private[MonorepoStepIO] (
      private val name: String,
      private val validateFn: T => MonorepoContext => IO[Unit],
      private val selectionBoundary: Boolean
  ) {

    def withValidation(f: T => MonorepoContext => IO[Unit]): ResourceGlobalBuilder[T] =
      new ResourceGlobalBuilder[T](name, f, selectionBoundary)

    def withSelectionBoundary: ResourceGlobalBuilder[T] =
      new ResourceGlobalBuilder[T](name, validateFn, true)

    def execute(f: T => MonorepoContext => IO[MonorepoContext]): T => MonorepoStepIO =
      t => Global(name, f(t), validateFn(t), selectionBoundary)

    def executeAction(f: T => MonorepoContext => IO[Unit]): T => MonorepoStepIO =
      t => Global(name, ctx => f(t)(ctx).as(ctx), validateFn(t), selectionBoundary)

    def validateOnly: T => MonorepoStepIO =
      t => Global(name, ctx => IO.pure(ctx), validateFn(t), selectionBoundary)
  }

  /** Fluent builder for resource-aware per-project steps. */
  final class ResourcePerProjectBuilder[T] private[MonorepoStepIO] (
      private val name: String,
      private val validateFn: T => (MonorepoContext, ProjectReleaseInfo) => IO[Unit],
      private val crossBuild: Boolean
  ) {

    def withCrossBuild: ResourcePerProjectBuilder[T] =
      new ResourcePerProjectBuilder[T](name, validateFn, true)

    def withValidation(
        f: T => (MonorepoContext, ProjectReleaseInfo) => IO[Unit]
    ): ResourcePerProjectBuilder[T] =
      new ResourcePerProjectBuilder[T](name, f, crossBuild)

    def execute(
        f: T => (MonorepoContext, ProjectReleaseInfo) => IO[MonorepoContext]
    ): T => MonorepoStepIO =
      t => PerProject(name, f(t), validateFn(t), crossBuild)

    def executeAction(
        f: T => (MonorepoContext, ProjectReleaseInfo) => IO[Unit]
    ): T => MonorepoStepIO =
      t => PerProject(name, (ctx, proj) => f(t)(ctx, proj).as(ctx), validateFn(t), crossBuild)

    def validateOnly: T => MonorepoStepIO =
      t => PerProject(name, (ctx, _) => IO.pure(ctx), validateFn(t), crossBuild)
  }

  /** Compose a sequence of monorepo steps into a two-phase IO program.
    * When `crossBuild` is true, steps with `enableCrossBuild` run once per `crossScalaVersions`.
    */
  def compose(steps: Seq[MonorepoStepIO], crossBuild: Boolean = false)(
      initialCtx: MonorepoContext
  ): IO[MonorepoContext] =
    MonorepoComposer.compose(steps, crossBuild)(initialCtx)
}
