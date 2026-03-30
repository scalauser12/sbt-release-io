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

  private[release] sealed trait ResourceStepFn[T] extends (T => MonorepoStepIO) {
    def name: String
    def scope: ResourceStepFn.Scope
  }

  private[release] object ResourceStepFn {
    sealed trait Scope

    object Scope {
      final case class Global(isSelectionBoundary: Boolean) extends Scope
      final case class PerProject(enableCrossBuild: Boolean) extends Scope
    }
  }

  private final case class ResourceGlobalStepFnImpl[T](
      name: String,
      validateFn: T => MonorepoContext => IO[Unit],
      executeFn: T => MonorepoContext => IO[MonorepoContext],
      isSelectionBoundary: Boolean
  ) extends ResourceStepFn[T] {
    override val scope: ResourceStepFn.Scope = ResourceStepFn.Scope.Global(isSelectionBoundary)

    override def apply(resource: T): MonorepoStepIO =
      Global(name, executeFn(resource), validateFn(resource), isSelectionBoundary)
  }

  private final case class ResourcePerProjectStepFnImpl[T](
      name: String,
      validateFn: T => (MonorepoContext, ProjectReleaseInfo) => IO[Unit],
      executeFn: T => (MonorepoContext, ProjectReleaseInfo) => IO[MonorepoContext],
      enableCrossBuild: Boolean
  ) extends ResourceStepFn[T] {
    override val scope: ResourceStepFn.Scope = ResourceStepFn.Scope.PerProject(enableCrossBuild)

    override def apply(resource: T): MonorepoStepIO =
      PerProject(name, executeFn(resource), validateFn(resource), enableCrossBuild)
  }

  /** A step that runs once globally (e.g., check clean working dir, push changes).
    *
    * @param isSelectionBoundary Internal flag — marks this step as the boundary between
    *   setup (sequential validate-then-execute) and main (batch validation then execution)
    *   phases. Only the built-in `detect-or-select-projects` step should set this.
    */
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

    private[monorepo] def withSelectionBoundary: GlobalBuilder =
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

    private[monorepo] def withSelectionBoundary: ResourceGlobalBuilder[T] =
      new ResourceGlobalBuilder[T](name, validateFn, true)

    def execute(f: T => MonorepoContext => IO[MonorepoContext]): T => MonorepoStepIO =
      ResourceGlobalStepFnImpl(
        name = name,
        validateFn = validateFn,
        executeFn = f,
        isSelectionBoundary = selectionBoundary
      )

    def executeAction(f: T => MonorepoContext => IO[Unit]): T => MonorepoStepIO =
      ResourceGlobalStepFnImpl(
        name = name,
        validateFn = validateFn,
        executeFn = t => ctx => f(t)(ctx).as(ctx),
        isSelectionBoundary = selectionBoundary
      )

    def validateOnly: T => MonorepoStepIO =
      ResourceGlobalStepFnImpl(
        name = name,
        validateFn = validateFn,
        executeFn = _ => ctx => IO.pure(ctx),
        isSelectionBoundary = selectionBoundary
      )
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
      ResourcePerProjectStepFnImpl(
        name = name,
        validateFn = validateFn,
        executeFn = f,
        enableCrossBuild = crossBuild
      )

    def executeAction(
        f: T => (MonorepoContext, ProjectReleaseInfo) => IO[Unit]
    ): T => MonorepoStepIO =
      ResourcePerProjectStepFnImpl(
        name = name,
        validateFn = validateFn,
        executeFn = t => (ctx, proj) => f(t)(ctx, proj).as(ctx),
        enableCrossBuild = crossBuild
      )

    def validateOnly: T => MonorepoStepIO =
      ResourcePerProjectStepFnImpl(
        name = name,
        validateFn = validateFn,
        executeFn = _ => (ctx, _) => IO.pure(ctx),
        enableCrossBuild = crossBuild
      )
  }

  /** Compose a sequence of monorepo steps into a two-phase IO program.
    * When `crossBuild` is true, steps with `enableCrossBuild` run once per `crossScalaVersions`.
    */
  def compose(steps: Seq[MonorepoStepIO], crossBuild: Boolean = false)(
      initialCtx: MonorepoContext
  ): IO[MonorepoContext] =
    MonorepoComposer.compose(steps, crossBuild)(initialCtx)
}
