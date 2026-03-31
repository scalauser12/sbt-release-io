package io.release.monorepo

import cats.effect.IO

/** A monorepo release step that can operate at global scope or per-project scope.
  *
  * Steps are executed in two phases by the composer:
  *  1. '''Validation phase''' — validates steps before execution and may thread updated
  *     internal runtime metadata through the phase.
  *  2. '''Execution phase''' — runs steps sequentially, with per-project failure isolation
  *     for [[MonorepoStepIO.PerProject]] steps and global failure propagation for
  *     [[MonorepoStepIO.Global]] steps.
  *
  * @see [[MonorepoStepIO.Global]]     for steps that run once (e.g., push changes)
  * @see [[MonorepoStepIO.PerProject]] for steps that run per subproject (e.g., publish)
  * @see [[MonorepoStepIO.compose]]    for the orchestration entry point
  */
sealed trait MonorepoStepIO {
  def name: String
}

object MonorepoStepIO {

  private type GlobalThreadedValidation     = MonorepoContext => IO[MonorepoContext]
  private type PerProjectThreadedValidation =
    (MonorepoContext, ProjectReleaseInfo) => IO[MonorepoContext]

  private def asThreadedValidation(
      validate: MonorepoContext => IO[Unit]
  ): GlobalThreadedValidation =
    ctx => validate(ctx).as(ctx)

  private def asThreadedValidation(
      validate: (MonorepoContext, ProjectReleaseInfo) => IO[Unit]
  ): PerProjectThreadedValidation =
    (ctx, project) => validate(ctx, project).as(ctx)

  private def appendThreadedValidation(
      existing: Option[GlobalThreadedValidation],
      next: GlobalThreadedValidation
  ): Option[GlobalThreadedValidation] =
    Some(existing.fold(next)(current => ctx => current(ctx).flatMap(next)))

  private def appendThreadedValidation(
      existing: Option[PerProjectThreadedValidation],
      next: PerProjectThreadedValidation
  ): Option[PerProjectThreadedValidation] =
    Some(
      existing.fold(next)(current =>
        (ctx, project) => current(ctx, project).flatMap(updatedCtx => next(updatedCtx, project))
      )
    )

  private def normalizedValidationPair(
      validate: MonorepoContext => IO[Unit],
      validateWithContext: Option[GlobalThreadedValidation]
  ): (MonorepoContext => IO[Unit], Option[GlobalThreadedValidation]) =
    validateWithContext match {
      case None           => (validate, None)
      case Some(threaded) =>
        val composed = appendThreadedValidation(
          Some(asThreadedValidation(validate)),
          threaded
        ).get
        ((ctx: MonorepoContext) => composed(ctx).void, Some(composed))
    }

  private def normalizedValidationPair(
      validate: (MonorepoContext, ProjectReleaseInfo) => IO[Unit],
      validateWithContext: Option[PerProjectThreadedValidation]
  ): (
      (MonorepoContext, ProjectReleaseInfo) => IO[Unit],
      Option[PerProjectThreadedValidation]
  ) =
    validateWithContext match {
      case None           => (validate, None)
      case Some(threaded) =>
        val composed = appendThreadedValidation(
          Some(asThreadedValidation(validate)),
          threaded
        ).get
        (
          (ctx: MonorepoContext, project: ProjectReleaseInfo) => composed(ctx, project).void,
          Some(composed)
        )
    }

  private[monorepo] def buildGlobal(
      name: String,
      execute: MonorepoContext => IO[MonorepoContext],
      validate: MonorepoContext => IO[Unit] = (_ctx: MonorepoContext) => IO.unit,
      isSelectionBoundary: Boolean = false,
      validateWithContext: Option[GlobalThreadedValidation] = None
  ): Global = {
    val (normalizedValidate, normalizedValidateWithContext) = normalizedValidationPair(
      validate,
      validateWithContext
    )
    new Global(
      name = name,
      execute = execute,
      validate = normalizedValidate,
      isSelectionBoundary = isSelectionBoundary,
      validateWithContext = normalizedValidateWithContext
    )
  }

  private[monorepo] def buildPerProject(
      name: String,
      execute: (MonorepoContext, ProjectReleaseInfo) => IO[MonorepoContext],
      validate: (MonorepoContext, ProjectReleaseInfo) => IO[Unit] =
        (_ctx: MonorepoContext, _project: ProjectReleaseInfo) => IO.unit,
      enableCrossBuild: Boolean = false,
      validateWithContext: Option[PerProjectThreadedValidation] = None
  ): PerProject = {
    val (normalizedValidate, normalizedValidateWithContext) = normalizedValidationPair(
      validate,
      validateWithContext
    )
    new PerProject(
      name = name,
      execute = execute,
      validate = normalizedValidate,
      enableCrossBuild = enableCrossBuild,
      validateWithContext = normalizedValidateWithContext
    )
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
      validate: MonorepoContext => IO[Unit] = (_ctx: MonorepoContext) => IO.unit,
      isSelectionBoundary: Boolean = false,
      validateWithContext: Option[MonorepoContext => IO[MonorepoContext]] = None
  ) extends MonorepoStepIO {

    private[monorepo] def threadedValidation: MonorepoContext => IO[MonorepoContext] =
      validateWithContext.getOrElse(ctx => this.validate.apply(ctx).as(ctx))
  }

  object Global {
    def apply(
        name: String,
        execute: MonorepoContext => IO[MonorepoContext],
        validate: MonorepoContext => IO[Unit] = (_ctx: MonorepoContext) => IO.unit,
        isSelectionBoundary: Boolean = false,
        validateWithContext: Option[GlobalThreadedValidation] = None
    ): Global =
      buildGlobal(name, execute, validate, isSelectionBoundary, validateWithContext)
  }

  /** A step that runs once per selected project in topological order
    * (e.g., set version, publish, tag).
    */
  case class PerProject(
      name: String,
      execute: (MonorepoContext, ProjectReleaseInfo) => IO[MonorepoContext],
      validate: (MonorepoContext, ProjectReleaseInfo) => IO[Unit] =
        (_ctx: MonorepoContext, _project: ProjectReleaseInfo) => IO.unit,
      enableCrossBuild: Boolean = false,
      validateWithContext: Option[
        (MonorepoContext, ProjectReleaseInfo) => IO[MonorepoContext]
      ] = None
  ) extends MonorepoStepIO {

    private[monorepo] def threadedValidation(
        ctx: MonorepoContext,
        project: ProjectReleaseInfo
    ): IO[MonorepoContext] =
      validateWithContext match {
        case Some(f) => f(ctx, project)
        case None    => this.validate.apply(ctx, project).as(ctx)
      }
  }

  object PerProject {
    def apply(
        name: String,
        execute: (MonorepoContext, ProjectReleaseInfo) => IO[MonorepoContext],
        validate: (MonorepoContext, ProjectReleaseInfo) => IO[Unit] =
          (_ctx: MonorepoContext, _project: ProjectReleaseInfo) => IO.unit,
        enableCrossBuild: Boolean = false,
        validateWithContext: Option[PerProjectThreadedValidation] = None
    ): PerProject =
      buildPerProject(name, execute, validate, enableCrossBuild, validateWithContext)
  }

  // ── Builder API ──────────────────────────────────────────────────────

  /** Start building a global step. */
  def global(name: String): GlobalBuilder = new GlobalBuilder(name, None, false)

  /** Start building a per-project step. */
  def perProject(name: String): PerProjectBuilder =
    new PerProjectBuilder(name, None, false)

  /** Start building a resource-aware global step. */
  def globalResource[T](name: String): ResourceGlobalBuilder[T] =
    new ResourceGlobalBuilder[T](name, _ => None, false)

  /** Start building a resource-aware per-project step. */
  def perProjectResource[T](name: String): ResourcePerProjectBuilder[T] =
    new ResourcePerProjectBuilder[T](name, _ => None, false)

  /** Fluent builder for global steps. */
  final class GlobalBuilder private[MonorepoStepIO] (
      private val name: String,
      private val validateWithContextFn: Option[GlobalThreadedValidation],
      private val selectionBoundary: Boolean
  ) {

    private def appendValidation(f: GlobalThreadedValidation): GlobalBuilder =
      new GlobalBuilder(
        name,
        appendThreadedValidation(validateWithContextFn, f),
        selectionBoundary
      )

    def withValidation(f: MonorepoContext => IO[Unit]): GlobalBuilder =
      appendValidation(asThreadedValidation(f))

    def withValidationContext(
        f: MonorepoContext => IO[MonorepoContext]
    ): GlobalBuilder =
      appendValidation(f)

    private[monorepo] def withSelectionBoundary: GlobalBuilder =
      new GlobalBuilder(name, validateWithContextFn, true)

    def execute(f: MonorepoContext => IO[MonorepoContext]): Global =
      buildGlobal(
        name = name,
        execute = f,
        isSelectionBoundary = selectionBoundary,
        validateWithContext = validateWithContextFn
      )

    def executeAction(f: MonorepoContext => IO[Unit]): Global =
      buildGlobal(
        name = name,
        execute = ctx => f(ctx).as(ctx),
        isSelectionBoundary = selectionBoundary,
        validateWithContext = validateWithContextFn
      )

    def validateOnly: Global =
      buildGlobal(
        name,
        ctx => IO.pure(ctx),
        isSelectionBoundary = selectionBoundary,
        validateWithContext = validateWithContextFn
      )
  }

  /** Fluent builder for per-project steps. */
  final class PerProjectBuilder private[MonorepoStepIO] (
      private val name: String,
      private val validateWithContextFn: Option[PerProjectThreadedValidation],
      private val crossBuild: Boolean
  ) {

    def withCrossBuild: PerProjectBuilder =
      new PerProjectBuilder(name, validateWithContextFn, true)

    private def appendValidation(f: PerProjectThreadedValidation): PerProjectBuilder =
      new PerProjectBuilder(name, appendThreadedValidation(validateWithContextFn, f), crossBuild)

    def withValidation(
        f: (MonorepoContext, ProjectReleaseInfo) => IO[Unit]
    ): PerProjectBuilder =
      appendValidation(asThreadedValidation(f))

    def withValidationContext(
        f: (MonorepoContext, ProjectReleaseInfo) => IO[MonorepoContext]
    ): PerProjectBuilder =
      appendValidation(f)

    def execute(
        f: (MonorepoContext, ProjectReleaseInfo) => IO[MonorepoContext]
    ): PerProject =
      buildPerProject(
        name = name,
        execute = f,
        enableCrossBuild = crossBuild,
        validateWithContext = validateWithContextFn
      )

    def executeAction(
        f: (MonorepoContext, ProjectReleaseInfo) => IO[Unit]
    ): PerProject =
      buildPerProject(
        name = name,
        execute = (ctx, proj) => f(ctx, proj).as(ctx),
        enableCrossBuild = crossBuild,
        validateWithContext = validateWithContextFn
      )

    def validateOnly: PerProject =
      buildPerProject(
        name = name,
        execute = (ctx, _) => IO.pure(ctx),
        enableCrossBuild = crossBuild,
        validateWithContext = validateWithContextFn
      )
  }

  /** Fluent builder for resource-aware global steps. */
  final class ResourceGlobalBuilder[T] private[MonorepoStepIO] (
      private val name: String,
      private val validateWithContextFn: T => Option[GlobalThreadedValidation],
      private val selectionBoundary: Boolean
  ) {

    private def appendValidation(
        f: T => GlobalThreadedValidation
    ): ResourceGlobalBuilder[T] =
      new ResourceGlobalBuilder[T](
        name,
        resource => appendThreadedValidation(validateWithContextFn(resource), f(resource)),
        selectionBoundary
      )

    def withValidation(f: T => MonorepoContext => IO[Unit]): ResourceGlobalBuilder[T] =
      appendValidation(resource => asThreadedValidation(f(resource)))

    def withValidationContext(
        f: T => MonorepoContext => IO[MonorepoContext]
    ): ResourceGlobalBuilder[T] =
      appendValidation(f)

    private[monorepo] def withSelectionBoundary: ResourceGlobalBuilder[T] =
      new ResourceGlobalBuilder[T](name, validateWithContextFn, true)

    def execute(f: T => MonorepoContext => IO[MonorepoContext]): T => MonorepoStepIO =
      resource =>
        buildGlobal(
          name = name,
          execute = f(resource),
          isSelectionBoundary = selectionBoundary,
          validateWithContext = validateWithContextFn(resource)
        )

    def executeAction(f: T => MonorepoContext => IO[Unit]): T => MonorepoStepIO =
      resource =>
        buildGlobal(
          name = name,
          execute = ctx => f(resource)(ctx).as(ctx),
          isSelectionBoundary = selectionBoundary,
          validateWithContext = validateWithContextFn(resource)
        )

    def validateOnly: T => MonorepoStepIO =
      resource =>
        buildGlobal(
          name = name,
          execute = ctx => IO.pure(ctx),
          isSelectionBoundary = selectionBoundary,
          validateWithContext = validateWithContextFn(resource)
        )
  }

  /** Fluent builder for resource-aware per-project steps. */
  final class ResourcePerProjectBuilder[T] private[MonorepoStepIO] (
      private val name: String,
      private val validateWithContextFn: T => Option[PerProjectThreadedValidation],
      private val crossBuild: Boolean
  ) {

    def withCrossBuild: ResourcePerProjectBuilder[T] =
      new ResourcePerProjectBuilder[T](name, validateWithContextFn, true)

    private def appendValidation(
        f: T => PerProjectThreadedValidation
    ): ResourcePerProjectBuilder[T] =
      new ResourcePerProjectBuilder[T](
        name,
        resource => appendThreadedValidation(validateWithContextFn(resource), f(resource)),
        crossBuild
      )

    def withValidation(
        f: T => (MonorepoContext, ProjectReleaseInfo) => IO[Unit]
    ): ResourcePerProjectBuilder[T] =
      appendValidation(resource => asThreadedValidation(f(resource)))

    def withValidationContext(
        f: T => (MonorepoContext, ProjectReleaseInfo) => IO[MonorepoContext]
    ): ResourcePerProjectBuilder[T] =
      appendValidation(f)

    def execute(
        f: T => (MonorepoContext, ProjectReleaseInfo) => IO[MonorepoContext]
    ): T => MonorepoStepIO =
      resource =>
        buildPerProject(
          name = name,
          execute = f(resource),
          enableCrossBuild = crossBuild,
          validateWithContext = validateWithContextFn(resource)
        )

    def executeAction(
        f: T => (MonorepoContext, ProjectReleaseInfo) => IO[Unit]
    ): T => MonorepoStepIO =
      resource =>
        buildPerProject(
          name = name,
          execute = (ctx, proj) => f(resource)(ctx, proj).as(ctx),
          enableCrossBuild = crossBuild,
          validateWithContext = validateWithContextFn(resource)
        )

    def validateOnly: T => MonorepoStepIO =
      resource =>
        buildPerProject(
          name = name,
          execute = (ctx, _) => IO.pure(ctx),
          enableCrossBuild = crossBuild,
          validateWithContext = validateWithContextFn(resource)
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
