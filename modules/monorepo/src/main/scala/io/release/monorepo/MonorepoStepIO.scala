package io.release.monorepo

import cats.effect.IO
import io.release.internal.StepKernel

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
@deprecated(
  "Use MonorepoGlobalHookIO/MonorepoProjectHookIO, MonorepoGlobalResourceHookIO/MonorepoProjectResourceHookIO, or grouped releaseIOMonorepoHooks*/releaseIOMonorepoPolicy* settings instead.",
  "0.8.1"
)
sealed trait MonorepoStepIO {
  def name: String
}

@deprecated(
  "Use MonorepoGlobalHookIO/MonorepoProjectHookIO, MonorepoGlobalResourceHookIO/MonorepoProjectResourceHookIO, or grouped releaseIOMonorepoHooks*/releaseIOMonorepoPolicy* settings instead.",
  "0.8.1"
)
@scala.annotation.nowarn("cat=deprecation")
object MonorepoStepIO {

  private type GlobalThreadedValidation     =
    StepKernel.ThreadedValidation[MonorepoContext]
  private type PerProjectThreadedValidation =
    StepKernel.ThreadedItemValidation[MonorepoContext, ProjectReleaseInfo]

  private[monorepo] val UnspecifiedGlobalValidate: MonorepoContext => IO[Unit] =
    (_ctx: MonorepoContext) =>
      IO.raiseError(
        new IllegalStateException("MonorepoStepIO.Global.copy validate sentinel should never run")
      )

  private[monorepo] val UnspecifiedGlobalValidateWithContextFn
      : MonorepoContext => IO[MonorepoContext] =
    (_ctx: MonorepoContext) =>
      IO.raiseError(
        new IllegalStateException(
          "MonorepoStepIO.Global.copy validateWithContext sentinel should never run"
        )
      )

  private[monorepo] val UnspecifiedGlobalValidateWithContext
      : Option[MonorepoContext => IO[MonorepoContext]] =
    Some(UnspecifiedGlobalValidateWithContextFn)

  private[monorepo] val UnspecifiedPerProjectValidate
      : (MonorepoContext, ProjectReleaseInfo) => IO[Unit] =
    (_ctx: MonorepoContext, _project: ProjectReleaseInfo) =>
      IO.raiseError(
        new IllegalStateException(
          "MonorepoStepIO.PerProject.copy validate sentinel should never run"
        )
      )

  private[monorepo] val UnspecifiedPerProjectValidateWithContextFn
      : (MonorepoContext, ProjectReleaseInfo) => IO[MonorepoContext] =
    (_ctx: MonorepoContext, _project: ProjectReleaseInfo) =>
      IO.raiseError(
        new IllegalStateException(
          "MonorepoStepIO.PerProject.copy validateWithContext sentinel should never run"
        )
      )

  private[monorepo] val UnspecifiedPerProjectValidateWithContext
      : Option[(MonorepoContext, ProjectReleaseInfo) => IO[MonorepoContext]] =
    Some(UnspecifiedPerProjectValidateWithContextFn)

  private[monorepo] def buildGlobal(
      name: String,
      execute: MonorepoContext => IO[MonorepoContext],
      validate: MonorepoContext => IO[Unit] = (_ctx: MonorepoContext) => IO.unit,
      isSelectionBoundary: Boolean = false,
      validateWithContext: Option[GlobalThreadedValidation] = None
  ): Global =
    new Global(name, execute, validate, isSelectionBoundary, validateWithContext)

  private[monorepo] def buildPerProject(
      name: String,
      execute: (MonorepoContext, ProjectReleaseInfo) => IO[MonorepoContext],
      validate: (MonorepoContext, ProjectReleaseInfo) => IO[Unit] =
        (_ctx: MonorepoContext, _project: ProjectReleaseInfo) => IO.unit,
      enableCrossBuild: Boolean = false,
      validateWithContext: Option[PerProjectThreadedValidation] = None
  ): PerProject =
    new PerProject(name, execute, validate, enableCrossBuild, validateWithContext)

  /** A step that runs once globally (e.g., check clean working dir, push changes).
    *
   * @param isSelectionBoundary Internal flag — marks this step as the boundary between
   *   setup (sequential validate-then-execute) and main (batch validation then execution)
   *   phases. Only the built-in `detect-or-select-projects` step should set this.
   */
  @deprecated(
    "Use MonorepoGlobalHookIO/MonorepoProjectHookIO, MonorepoGlobalResourceHookIO/MonorepoProjectResourceHookIO, or grouped releaseIOMonorepoHooks*/releaseIOMonorepoPolicy* settings instead.",
    "0.8.1"
  )
  case class Global private[monorepo] (
      name: String,
      execute: MonorepoContext => IO[MonorepoContext],
      private val rawValidate: MonorepoContext => IO[Unit] = (_ctx: MonorepoContext) => IO.unit,
      isSelectionBoundary: Boolean = false,
      private val rawValidateWithContext: Option[MonorepoContext => IO[MonorepoContext]] = None
  ) extends MonorepoStepIO {

    // Keep the caller-provided validation inputs raw so apply(...), copy(...), and public
    // round-tripping preserve the same invariants without double-wrapping already-composed
    // validation functions.
    private lazy val normalizedValidation =
      StepKernel.normalizedValidationPair[MonorepoContext](rawValidate, rawValidateWithContext)

    // Public validate exposes the normalized IO[Unit] view.
    def validate: MonorepoContext => IO[Unit] =
      normalizedValidation._1

    // Public validateWithContext preserves the raw stored threaded hook for round-trips.
    def validateWithContext: Option[MonorepoContext => IO[MonorepoContext]] =
      rawValidateWithContext

    def copy(
        name: String = this.name,
        execute: MonorepoContext => IO[MonorepoContext] = this.execute,
        validate: MonorepoContext => IO[Unit] = MonorepoStepIO.UnspecifiedGlobalValidate,
        isSelectionBoundary: Boolean = this.isSelectionBoundary,
        validateWithContext: Option[MonorepoContext => IO[MonorepoContext]] =
          MonorepoStepIO.UnspecifiedGlobalValidateWithContext
    ): Global = {
      val (nextRawValidate, nextRawValidateWithContext) =
        StepKernel.resolveSingleCopyFields(
          currentRawValidate = rawValidate,
          currentRawValidateWithContext = rawValidateWithContext,
          currentValidate = this.validate,
          currentNormalizedValidateWithContext = normalizedValidation._2,
          requestedValidate = validate,
          unspecifiedValidate = MonorepoStepIO.UnspecifiedGlobalValidate,
          requestedValidateWithContext = validateWithContext,
          unspecifiedValidateWithContext = MonorepoStepIO.UnspecifiedGlobalValidateWithContext
        )

      new Global(name, execute, nextRawValidate, isSelectionBoundary, nextRawValidateWithContext)
    }

    private[monorepo] def threadedValidation: MonorepoContext => IO[MonorepoContext] =
      normalizedValidation._2.getOrElse(ctx => this.validate.apply(ctx).as(ctx))
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

    def unapply(
        step: Global
    ): Option[
      (
          String,
          MonorepoContext => IO[MonorepoContext],
          MonorepoContext => IO[Unit],
          Boolean,
          Option[GlobalThreadedValidation]
      )
    ] =
      Some(
        (
          step.name,
          step.execute,
          step.rawValidate,
          step.isSelectionBoundary,
          step.rawValidateWithContext
        )
      )
  }

  /** A step that runs once per selected project in topological order
    * (e.g., set version, publish, tag).
    */
  @deprecated(
    "Use MonorepoGlobalHookIO/MonorepoProjectHookIO, MonorepoGlobalResourceHookIO/MonorepoProjectResourceHookIO, or grouped releaseIOMonorepoHooks*/releaseIOMonorepoPolicy* settings instead.",
    "0.8.1"
  )
  case class PerProject private[monorepo] (
      name: String,
      execute: (MonorepoContext, ProjectReleaseInfo) => IO[MonorepoContext],
      private val rawValidate: (MonorepoContext, ProjectReleaseInfo) => IO[Unit] =
        (_ctx: MonorepoContext, _project: ProjectReleaseInfo) => IO.unit,
      enableCrossBuild: Boolean = false,
      private val rawValidateWithContext: Option[
        (MonorepoContext, ProjectReleaseInfo) => IO[MonorepoContext]
      ] = None
  ) extends MonorepoStepIO {

    // Keep the caller-provided validation inputs raw so apply(...), copy(...), and public
    // round-tripping preserve the same invariants without double-wrapping already-composed
    // validation functions.
    private lazy val normalizedValidation =
      StepKernel.normalizedValidationPair[MonorepoContext, ProjectReleaseInfo](
        rawValidate,
        rawValidateWithContext
      )

    // Public validate exposes the normalized IO[Unit] view.
    def validate: (MonorepoContext, ProjectReleaseInfo) => IO[Unit] =
      normalizedValidation._1

    // Public validateWithContext preserves the raw stored threaded hook for round-trips.
    def validateWithContext: Option[
      (MonorepoContext, ProjectReleaseInfo) => IO[MonorepoContext]
    ] =
      rawValidateWithContext

    def copy(
        name: String = this.name,
        execute: (MonorepoContext, ProjectReleaseInfo) => IO[MonorepoContext] = this.execute,
        validate: (MonorepoContext, ProjectReleaseInfo) => IO[Unit] =
          MonorepoStepIO.UnspecifiedPerProjectValidate,
        enableCrossBuild: Boolean = this.enableCrossBuild,
        validateWithContext: Option[
          (MonorepoContext, ProjectReleaseInfo) => IO[MonorepoContext]
        ] = MonorepoStepIO.UnspecifiedPerProjectValidateWithContext
    ): PerProject = {
      val (nextRawValidate, nextRawValidateWithContext) =
        StepKernel.resolveItemCopyFields(
          currentRawValidate = rawValidate,
          currentRawValidateWithContext = rawValidateWithContext,
          currentValidate = this.validate,
          currentNormalizedValidateWithContext = normalizedValidation._2,
          requestedValidate = validate,
          unspecifiedValidate = MonorepoStepIO.UnspecifiedPerProjectValidate,
          requestedValidateWithContext = validateWithContext,
          unspecifiedValidateWithContext = MonorepoStepIO.UnspecifiedPerProjectValidateWithContext
        )

      new PerProject(
        name,
        execute,
        nextRawValidate,
        enableCrossBuild,
        nextRawValidateWithContext
      )
    }

    private[monorepo] def threadedValidation(
        ctx: MonorepoContext,
        project: ProjectReleaseInfo
    ): IO[MonorepoContext] =
      normalizedValidation._2 match {
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

    def unapply(
        step: PerProject
    ): Option[
      (
          String,
          (MonorepoContext, ProjectReleaseInfo) => IO[MonorepoContext],
          (MonorepoContext, ProjectReleaseInfo) => IO[Unit],
          Boolean,
          Option[PerProjectThreadedValidation]
      )
    ] =
      Some(
        (
          step.name,
          step.execute,
          step.rawValidate,
          step.enableCrossBuild,
          step.rawValidateWithContext
        )
      )
  }

  // ── Builder API ──────────────────────────────────────────────────────

  /** Start building a global step. */
  def global(name: String): GlobalBuilder =
    new GlobalBuilder(StepKernel.SingleBuilderState[MonorepoContext](name))

  /** Start building a per-project step. */
  def perProject(name: String): PerProjectBuilder =
    new PerProjectBuilder(
      StepKernel.ItemBuilderState[MonorepoContext, ProjectReleaseInfo](name)
    )

  /** Start building a resource-aware global step. */
  def globalResource[T](name: String): ResourceGlobalBuilder[T] =
    new ResourceGlobalBuilder[T](
      StepKernel.SingleResourceBuilderState[T, MonorepoContext](name)
    )

  /** Start building a resource-aware per-project step. */
  def perProjectResource[T](name: String): ResourcePerProjectBuilder[T] =
    new ResourcePerProjectBuilder[T](
      StepKernel.ItemResourceBuilderState[T, MonorepoContext, ProjectReleaseInfo](name)
    )

  /** Fluent builder for global steps. */
  @deprecated(
    "Use MonorepoGlobalHookIO/MonorepoProjectHookIO, MonorepoGlobalResourceHookIO/MonorepoProjectResourceHookIO, or grouped releaseIOMonorepoHooks*/releaseIOMonorepoPolicy* settings instead.",
    "0.8.1"
  )
  final class GlobalBuilder private[MonorepoStepIO] (
      private val state: StepKernel.SingleBuilderState[MonorepoContext]
  ) {

    def withValidation(f: MonorepoContext => IO[Unit]): GlobalBuilder =
      new GlobalBuilder(state.appendPlainValidation(f))

    def withValidationContext(
        f: MonorepoContext => IO[MonorepoContext]
    ): GlobalBuilder =
      new GlobalBuilder(state.appendValidation(f))

    private[monorepo] def withSelectionBoundary: GlobalBuilder =
      new GlobalBuilder(state.withFlag)

    def execute(f: MonorepoContext => IO[MonorepoContext]): Global =
      buildGlobal(
        name = state.name,
        execute = f,
        isSelectionBoundary = state.flagEnabled,
        validateWithContext = state.validateWithContext
      )

    def executeAction(f: MonorepoContext => IO[Unit]): Global =
      buildGlobal(
        name = state.name,
        execute = ctx => f(ctx).as(ctx),
        isSelectionBoundary = state.flagEnabled,
        validateWithContext = state.validateWithContext
      )

    def validateOnly: Global =
      buildGlobal(
        state.name,
        ctx => IO.pure(ctx),
        isSelectionBoundary = state.flagEnabled,
        validateWithContext = state.validateWithContext
      )
  }

  /** Fluent builder for per-project steps. */
  @deprecated(
    "Use MonorepoGlobalHookIO/MonorepoProjectHookIO, MonorepoGlobalResourceHookIO/MonorepoProjectResourceHookIO, or grouped releaseIOMonorepoHooks*/releaseIOMonorepoPolicy* settings instead.",
    "0.8.1"
  )
  final class PerProjectBuilder private[MonorepoStepIO] (
      private val state: StepKernel.ItemBuilderState[MonorepoContext, ProjectReleaseInfo]
  ) {

    def withCrossBuild: PerProjectBuilder =
      new PerProjectBuilder(state.withFlag)

    def withValidation(
        f: (MonorepoContext, ProjectReleaseInfo) => IO[Unit]
    ): PerProjectBuilder =
      new PerProjectBuilder(state.appendPlainValidation(f))

    def withValidationContext(
        f: (MonorepoContext, ProjectReleaseInfo) => IO[MonorepoContext]
    ): PerProjectBuilder =
      new PerProjectBuilder(state.appendValidation(f))

    def execute(
        f: (MonorepoContext, ProjectReleaseInfo) => IO[MonorepoContext]
    ): PerProject =
      buildPerProject(
        name = state.name,
        execute = f,
        enableCrossBuild = state.flagEnabled,
        validateWithContext = state.validateWithContext
      )

    def executeAction(
        f: (MonorepoContext, ProjectReleaseInfo) => IO[Unit]
    ): PerProject =
      buildPerProject(
        name = state.name,
        execute = (ctx, proj) => f(ctx, proj).as(ctx),
        enableCrossBuild = state.flagEnabled,
        validateWithContext = state.validateWithContext
      )

    def validateOnly: PerProject =
      buildPerProject(
        name = state.name,
        execute = (ctx, _) => IO.pure(ctx),
        enableCrossBuild = state.flagEnabled,
        validateWithContext = state.validateWithContext
      )
  }

  /** Fluent builder for resource-aware global steps. */
  @deprecated(
    "Use MonorepoGlobalHookIO/MonorepoProjectHookIO, MonorepoGlobalResourceHookIO/MonorepoProjectResourceHookIO, or grouped releaseIOMonorepoHooks*/releaseIOMonorepoPolicy* settings instead.",
    "0.8.1"
  )
  final class ResourceGlobalBuilder[T] private[MonorepoStepIO] (
      private val state: StepKernel.SingleResourceBuilderState[T, MonorepoContext]
  ) {

    def withValidation(f: T => MonorepoContext => IO[Unit]): ResourceGlobalBuilder[T] =
      new ResourceGlobalBuilder[T](state.appendPlainValidation(f))

    def withValidationContext(
        f: T => MonorepoContext => IO[MonorepoContext]
    ): ResourceGlobalBuilder[T] =
      new ResourceGlobalBuilder[T](state.appendValidation(f))

    private[monorepo] def withSelectionBoundary: ResourceGlobalBuilder[T] =
      new ResourceGlobalBuilder[T](state.withFlag)

    def execute(f: T => MonorepoContext => IO[MonorepoContext]): T => MonorepoStepIO =
      resource =>
        buildGlobal(
          name = state.name,
          execute = f(resource),
          isSelectionBoundary = state.flagEnabled,
          validateWithContext = state.validateWithContext(resource)
        )

    def executeAction(f: T => MonorepoContext => IO[Unit]): T => MonorepoStepIO =
      resource =>
        buildGlobal(
          name = state.name,
          execute = ctx => f(resource)(ctx).as(ctx),
          isSelectionBoundary = state.flagEnabled,
          validateWithContext = state.validateWithContext(resource)
        )

    def validateOnly: T => MonorepoStepIO =
      resource =>
        buildGlobal(
          name = state.name,
          execute = ctx => IO.pure(ctx),
          isSelectionBoundary = state.flagEnabled,
          validateWithContext = state.validateWithContext(resource)
        )
  }

  /** Fluent builder for resource-aware per-project steps. */
  @deprecated(
    "Use MonorepoGlobalHookIO/MonorepoProjectHookIO, MonorepoGlobalResourceHookIO/MonorepoProjectResourceHookIO, or grouped releaseIOMonorepoHooks*/releaseIOMonorepoPolicy* settings instead.",
    "0.8.1"
  )
  final class ResourcePerProjectBuilder[T] private[MonorepoStepIO] (
      private val state: StepKernel.ItemResourceBuilderState[
        T,
        MonorepoContext,
        ProjectReleaseInfo
      ]
  ) {

    def withCrossBuild: ResourcePerProjectBuilder[T] =
      new ResourcePerProjectBuilder[T](state.withFlag)

    def withValidation(
        f: T => (MonorepoContext, ProjectReleaseInfo) => IO[Unit]
    ): ResourcePerProjectBuilder[T] =
      new ResourcePerProjectBuilder[T](state.appendPlainValidation(f))

    def withValidationContext(
        f: T => (MonorepoContext, ProjectReleaseInfo) => IO[MonorepoContext]
    ): ResourcePerProjectBuilder[T] =
      new ResourcePerProjectBuilder[T](state.appendValidation(f))

    def execute(
        f: T => (MonorepoContext, ProjectReleaseInfo) => IO[MonorepoContext]
    ): T => MonorepoStepIO =
      resource =>
        buildPerProject(
          name = state.name,
          execute = f(resource),
          enableCrossBuild = state.flagEnabled,
          validateWithContext = state.validateWithContext(resource)
        )

    def executeAction(
        f: T => (MonorepoContext, ProjectReleaseInfo) => IO[Unit]
    ): T => MonorepoStepIO =
      resource =>
        buildPerProject(
          name = state.name,
          execute = (ctx, proj) => f(resource)(ctx, proj).as(ctx),
          enableCrossBuild = state.flagEnabled,
          validateWithContext = state.validateWithContext(resource)
        )

    def validateOnly: T => MonorepoStepIO =
      resource =>
        buildPerProject(
          name = state.name,
          execute = (ctx, _) => IO.pure(ctx),
          enableCrossBuild = state.flagEnabled,
          validateWithContext = state.validateWithContext(resource)
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
