package io.release.monorepo

import cats.effect.IO
import io.release.internal.ExecutionEngine
import io.release.internal.ReleaseLogPrefixes
import io.release.internal.StepKernel
import io.release.monorepo.steps.MonorepoCrossBuild

/** Internal monorepo release steps used by the composer and preflight. */
private[monorepo] sealed trait MonorepoProcessStep {
  def name: String

  private[monorepo] def isSelectionBoundary: Boolean

  private[monorepo] def validationStep(
      crossBuild: Boolean
  ): ExecutionEngine.ValidationStep[MonorepoContext]

  private[monorepo] def actionStep(
      crossBuild: Boolean
  ): ExecutionEngine.ActionStep[MonorepoContext]
}

private[monorepo] object MonorepoProcessStep {

  private val LogPrefix = ReleaseLogPrefixes.Monorepo

  private type GlobalThreadedValidation     =
    StepKernel.ThreadedValidation[MonorepoContext]
  private type PerProjectThreadedValidation =
    StepKernel.ThreadedItemValidation[MonorepoContext, ProjectReleaseInfo]

  private[monorepo] val UnspecifiedGlobalValidate: MonorepoContext => IO[Unit] =
    (_ctx: MonorepoContext) =>
      IO.raiseError(
        new IllegalStateException(
          "MonorepoProcessStep.Global.copy validate sentinel should never run"
        )
      )

  private[monorepo] val UnspecifiedGlobalValidateWithContextFn
      : MonorepoContext => IO[MonorepoContext] =
    (_ctx: MonorepoContext) =>
      IO.raiseError(
        new IllegalStateException(
          "MonorepoProcessStep.Global.copy validateWithContext sentinel should never run"
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
          "MonorepoProcessStep.PerProject.copy validate sentinel should never run"
        )
      )

  private[monorepo] val UnspecifiedPerProjectValidateWithContextFn
      : (MonorepoContext, ProjectReleaseInfo) => IO[MonorepoContext] =
    (_ctx: MonorepoContext, _project: ProjectReleaseInfo) =>
      IO.raiseError(
        new IllegalStateException(
          "MonorepoProcessStep.PerProject.copy validateWithContext sentinel should never run"
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

  final case class Global private[monorepo] (
      name: String,
      execute: MonorepoContext => IO[MonorepoContext],
      private val rawValidate: MonorepoContext => IO[Unit] = (_ctx: MonorepoContext) => IO.unit,
      isSelectionBoundary: Boolean = false,
      private val rawValidateWithContext: Option[MonorepoContext => IO[MonorepoContext]] = None
  ) extends MonorepoProcessStep {

    private lazy val normalizedValidation =
      StepKernel.normalizedValidationPair[MonorepoContext](rawValidate, rawValidateWithContext)

    def validate: MonorepoContext => IO[Unit] =
      normalizedValidation._1

    def validateWithContext: Option[MonorepoContext => IO[MonorepoContext]] =
      rawValidateWithContext

    def copy(
        name: String = this.name,
        execute: MonorepoContext => IO[MonorepoContext] = this.execute,
        validate: MonorepoContext => IO[Unit] = MonorepoProcessStep.UnspecifiedGlobalValidate,
        isSelectionBoundary: Boolean = this.isSelectionBoundary,
        validateWithContext: Option[MonorepoContext => IO[MonorepoContext]] =
          MonorepoProcessStep.UnspecifiedGlobalValidateWithContext
    ): Global = {
      val (nextRawValidate, nextRawValidateWithContext) =
        StepKernel.resolveSingleCopyFields(
          currentRawValidate = rawValidate,
          currentRawValidateWithContext = rawValidateWithContext,
          currentValidate = this.validate,
          currentNormalizedValidateWithContext = normalizedValidation._2,
          requestedValidate = validate,
          unspecifiedValidate = MonorepoProcessStep.UnspecifiedGlobalValidate,
          requestedValidateWithContext = validateWithContext,
          unspecifiedValidateWithContext = MonorepoProcessStep.UnspecifiedGlobalValidateWithContext
        )

      new Global(name, execute, nextRawValidate, isSelectionBoundary, nextRawValidateWithContext)
    }

    private[monorepo] def threadedValidation: MonorepoContext => IO[MonorepoContext] =
      normalizedValidation._2.getOrElse(ctx => this.validate.apply(ctx).as(ctx))

    override private[monorepo] def validationStep(
        crossBuild: Boolean
    ): ExecutionEngine.ValidationStep[MonorepoContext] =
      ExecutionEngine.ValidationStep(name, threadedValidation)

    override private[monorepo] def actionStep(
        crossBuild: Boolean
    ): ExecutionEngine.ActionStep[MonorepoContext] =
      ExecutionEngine.ActionStep(
        name,
        ExecutionEngine.withErrorRecovery[MonorepoContext](LogPrefix) { currentCtx =>
          IO.blocking(currentCtx.state.log.info(s"$LogPrefix $name")) *>
            execute(currentCtx)
        }
      )
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

  final case class PerProject private[monorepo] (
      name: String,
      execute: (MonorepoContext, ProjectReleaseInfo) => IO[MonorepoContext],
      private val rawValidate: (MonorepoContext, ProjectReleaseInfo) => IO[Unit] =
        (_ctx: MonorepoContext, _project: ProjectReleaseInfo) => IO.unit,
      enableCrossBuild: Boolean = false,
      private val rawValidateWithContext: Option[
        (MonorepoContext, ProjectReleaseInfo) => IO[MonorepoContext]
      ] = None
  ) extends MonorepoProcessStep {

    private lazy val normalizedValidation =
      StepKernel.normalizedValidationPair[MonorepoContext, ProjectReleaseInfo](
        rawValidate,
        rawValidateWithContext
      )

    def validate: (MonorepoContext, ProjectReleaseInfo) => IO[Unit] =
      normalizedValidation._1

    def validateWithContext: Option[
      (MonorepoContext, ProjectReleaseInfo) => IO[MonorepoContext]
    ] =
      rawValidateWithContext

    def copy(
        name: String = this.name,
        execute: (MonorepoContext, ProjectReleaseInfo) => IO[MonorepoContext] = this.execute,
        validate: (MonorepoContext, ProjectReleaseInfo) => IO[Unit] =
          MonorepoProcessStep.UnspecifiedPerProjectValidate,
        enableCrossBuild: Boolean = this.enableCrossBuild,
        validateWithContext: Option[
          (MonorepoContext, ProjectReleaseInfo) => IO[MonorepoContext]
        ] = MonorepoProcessStep.UnspecifiedPerProjectValidateWithContext
    ): PerProject = {
      val (nextRawValidate, nextRawValidateWithContext) =
        StepKernel.resolveItemCopyFields(
          currentRawValidate = rawValidate,
          currentRawValidateWithContext = rawValidateWithContext,
          currentValidate = this.validate,
          currentNormalizedValidateWithContext = normalizedValidation._2,
          requestedValidate = validate,
          unspecifiedValidate = MonorepoProcessStep.UnspecifiedPerProjectValidate,
          requestedValidateWithContext = validateWithContext,
          unspecifiedValidateWithContext =
            MonorepoProcessStep.UnspecifiedPerProjectValidateWithContext
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

    override private[monorepo] def isSelectionBoundary: Boolean = false

    override private[monorepo] def validationStep(
        crossBuild: Boolean
    ): ExecutionEngine.ValidationStep[MonorepoContext] =
      ExecutionEngine.ValidationStep(
        name,
        ctx =>
          MonorepoCrossBuild.validatePerProjectWithCrossBuild(
            ctx,
            threadedValidation,
            crossBuild,
            enableCrossBuild
          )
      )

    override private[monorepo] def actionStep(
        crossBuild: Boolean
    ): ExecutionEngine.ActionStep[MonorepoContext] = {
      val logged: (MonorepoContext, ProjectReleaseInfo) => IO[MonorepoContext] =
        (currentCtx, project) =>
          IO.blocking(currentCtx.state.log.info(s"$LogPrefix $name [${project.name}]")) *>
            execute(currentCtx, project)

      ExecutionEngine.ActionStep(
        name,
        ExecutionEngine.withErrorRecovery[MonorepoContext](LogPrefix)(ctx =>
          MonorepoCrossBuild
            .runPerProjectWithCrossBuild(ctx, logged, crossBuild, enableCrossBuild)
        )
      )
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

  def global(name: String): GlobalBuilder =
    new GlobalBuilder(StepKernel.SingleBuilderState[MonorepoContext](name))

  def perProject(name: String): PerProjectBuilder =
    new PerProjectBuilder(
      StepKernel.ItemBuilderState[MonorepoContext, ProjectReleaseInfo](name)
    )

  def globalResource[T](name: String): ResourceGlobalBuilder[T] =
    new ResourceGlobalBuilder[T](
      StepKernel.SingleResourceBuilderState[T, MonorepoContext](name)
    )

  def perProjectResource[T](name: String): ResourcePerProjectBuilder[T] =
    new ResourcePerProjectBuilder[T](
      StepKernel.ItemResourceBuilderState[T, MonorepoContext, ProjectReleaseInfo](name)
    )

  final class GlobalBuilder private[MonorepoProcessStep] (
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

  final class PerProjectBuilder private[MonorepoProcessStep] (
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

  final class ResourceGlobalBuilder[T] private[MonorepoProcessStep] (
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

    def execute(f: T => MonorepoContext => IO[MonorepoContext]): T => MonorepoProcessStep =
      resource =>
        buildGlobal(
          name = state.name,
          execute = f(resource),
          isSelectionBoundary = state.flagEnabled,
          validateWithContext = state.validateWithContext(resource)
        )

    def executeAction(f: T => MonorepoContext => IO[Unit]): T => MonorepoProcessStep =
      resource =>
        buildGlobal(
          name = state.name,
          execute = ctx => f(resource)(ctx).as(ctx),
          isSelectionBoundary = state.flagEnabled,
          validateWithContext = state.validateWithContext(resource)
        )

    def validateOnly: T => MonorepoProcessStep =
      resource =>
        buildGlobal(
          name = state.name,
          execute = ctx => IO.pure(ctx),
          isSelectionBoundary = state.flagEnabled,
          validateWithContext = state.validateWithContext(resource)
        )
  }

  final class ResourcePerProjectBuilder[T] private[MonorepoProcessStep] (
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
    ): T => MonorepoProcessStep =
      resource =>
        buildPerProject(
          name = state.name,
          execute = f(resource),
          enableCrossBuild = state.flagEnabled,
          validateWithContext = state.validateWithContext(resource)
        )

    def executeAction(
        f: T => (MonorepoContext, ProjectReleaseInfo) => IO[Unit]
    ): T => MonorepoProcessStep =
      resource =>
        buildPerProject(
          name = state.name,
          execute = (ctx, proj) => f(resource)(ctx, proj).as(ctx),
          enableCrossBuild = state.flagEnabled,
          validateWithContext = state.validateWithContext(resource)
        )

    def validateOnly: T => MonorepoProcessStep =
      resource =>
        buildPerProject(
          name = state.name,
          execute = (ctx, _) => IO.pure(ctx),
          enableCrossBuild = state.flagEnabled,
          validateWithContext = state.validateWithContext(resource)
        )
  }

  def compose(steps: Seq[MonorepoProcessStep], crossBuild: Boolean = false)(
      initialCtx: MonorepoContext
  ): IO[MonorepoContext] =
    MonorepoComposer.compose(steps, crossBuild)(initialCtx)
}
