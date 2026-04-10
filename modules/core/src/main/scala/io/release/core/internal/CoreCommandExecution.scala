package io.release.core.internal

import cats.effect.IO
import cats.effect.Resource
import io.release.ReleaseComposer
import io.release.ReleaseContext
import io.release.ReleaseResourceHooks
import io.release.VcsOps
import io.release.core.internal.CoreStepAliases.Step
import io.release.runtime.ReleaseDecisionDefaults
import io.release.runtime.ReleaseLogPrefixes
import io.release.runtime.command.CheckModeOutput
import io.release.runtime.command.CommandStateSupport
import io.release.runtime.command.ReleaseCommandCompilation
import io.release.runtime.command.ReleaseCommandRunner
import io.release.runtime.workflow.DecisionDefaultsSupport
import sbt.{internal as _, *}

/** Internal runtime helpers for core command planning and execution.
  *
  * == Core command path ==
  *
  * {{{
  * sbt "releaseIO [flags]"
  *   → ReleasePluginIO registers the sbt command
  *   → CoreCommandExecution.buildCommandInputs   (parse CLI flags into CoreReleasePlan)
  *   → CoreCommandExecution.runPreparedCommand    (resolve hooks, compile into steps)
  *   → ReleaseComposer.compose                    (wrap steps as PreparedStep, cross-build)
  *   → ExecutionEngine.runMainSegment             (validate all, then execute all)
  * }}}
  *
  * Public plugin extension points stay on [[io.release.ReleasePluginIOLike]]; this object owns
  * the private command plumbing so the plugin trait can read top-down.
  */
private[release] object CoreCommandExecution {

  final case class CommandRuntime[T](
      commandName: String,
      resource: Resource[IO, T],
      resolveResourceHooks: State => ReleaseResourceHooks[T],
      resolveCrossBuildEnabled: State => Boolean,
      resolveSkipPublishEnabled: State => Boolean,
      resolveInteractiveEnabled: State => Boolean,
      initialContext: (State, Boolean, Boolean, Boolean) => IO[ReleaseContext]
  )

  final case class CoreCommandInputs(
      cleanState: State,
      skipTests: Boolean,
      skipPublish: Boolean,
      interactive: Boolean,
      crossEnabled: Boolean,
      plan: CoreReleasePlan
  )

  final case class CompiledSteps(
      steps: Seq[Step]
  )

  def doHelp(state: State, commandName: String): State =
    ReleaseCommandRunner.runSync(state, ReleaseLogPrefixes.Core) {
      ReleaseCommandRunner
        .logLines(state, ReleaseLogPrefixes.Core, CorePreflight.helpLines(commandName))
        .as(state)
    }

  def doRelease[T](
      state: State,
      args: Seq[ReleaseCli.Arg],
      runtime: CommandRuntime[T]
  ): State =
    ReleaseCommandCompilation.runPreparedCommand(
      state = state,
      cleanState = state => CommandStateSupport.cleanReleaseState(state),
      logPrefix = ReleaseLogPrefixes.Core
    )(
      cleanState =>
        IO.pure(
          Right(
            buildCommandInputs(
              cleanState,
              args,
              warnOnDuplicates = true,
              interactiveEnabled = runtime.resolveInteractiveEnabled(cleanState),
              runtime
            )
          )
        ),
      (inputs: CoreCommandInputs) => runPlannedRelease(inputs, runtime)
    )

  def doCheck[T](
      state: State,
      args: Seq[ReleaseCli.Arg],
      runtime: CommandRuntime[T]
  ): State =
    ReleaseCommandCompilation.runPreparedCommand(
      state = state,
      cleanState = state => CommandStateSupport.cleanReleaseState(state),
      logPrefix = ReleaseLogPrefixes.Core
    )(
      cleanState =>
        IO.pure(
          Right(
            buildCommandInputs(
              cleanState,
              args,
              warnOnDuplicates = false,
              interactiveEnabled = false,
              runtime
            )
          )
        ),
      (inputs: CoreCommandInputs) => runPlannedCheck(inputs, runtime)
    )

  def resolveProcessMode[T](
      state: State,
      runtime: CommandRuntime[T]
  ): IO[CompiledSteps] =
    compileMergedSteps(state, runtime, maybeResource = None)

  def resolveReleaseRun[T](
      state: State,
      resourceValue: T,
      runtime: CommandRuntime[T]
  ): IO[CompiledSteps] =
    compileMergedSteps(state, runtime, maybeResource = Some(resourceValue))

  private def compileMergedSteps[T](
      state: State,
      runtime: CommandRuntime[T],
      maybeResource: Option[T]
  ): IO[CompiledSteps] =
    ReleaseCommandCompilation
      .blockingMergeAndCompile(
        state = state,
        maybeResource = maybeResource,
        resolveHooks = CoreHookConfiguration.resolve,
        resolveResourceHooks = runtime.resolveResourceHooks,
        materialize = (rh: ReleaseResourceHooks[T], opt: Option[T]) =>
          ReleaseResourceHooks.materialize(rh, opt),
        merge = (a: CoreHookConfiguration, b: CoreHookConfiguration) => a.mergeWith(b),
        compile = CoreLifecycle.compile
      )
      .map(steps => CompiledSteps(steps))

  private def buildCommandInputs[T](
      cleanState: State,
      args: Seq[ReleaseCli.Arg],
      warnOnDuplicates: Boolean,
      interactiveEnabled: Boolean,
      runtime: CommandRuntime[T]
  ): CoreCommandInputs = {
    import ReleaseCli.Arg.*

    def allArgs[A](extract: PartialFunction[ReleaseCli.Arg, A]): Seq[A] =
      args.collect(extract)

    val useDefaults   = args.contains(WithDefaults)
    val skipTests     = args.contains(SkipTests)
    val crossFromArgs = args.contains(CrossBuild)
    val crossEnabled  = runtime.resolveCrossBuildEnabled(cleanState) || crossFromArgs
    val skipPublish   = runtime.resolveSkipPublishEnabled(cleanState)
    val interactive   = interactiveEnabled

    val releaseVersionArg = DecisionDefaultsSupport.resolveLast(
      cleanState,
      ReleaseLogPrefixes.Core,
      "release-version",
      allArgs { case ReleaseVersion(value) => value },
      (value: String) => value,
      warnOnDuplicates
    )
    val nextVersionArg    = DecisionDefaultsSupport.resolveLast(
      cleanState,
      ReleaseLogPrefixes.Core,
      "next-version",
      allArgs { case NextVersion(value) => value },
      (value: String) => value,
      warnOnDuplicates
    )

    CoreCommandInputs(
      cleanState = cleanState,
      skipTests = skipTests,
      skipPublish = skipPublish,
      interactive = interactive,
      crossEnabled = crossEnabled,
      plan = CoreReleasePlan.fromFlags(
        useDefaults = useDefaults,
        skipTests = skipTests,
        skipPublish = skipPublish,
        interactive = interactive,
        crossBuild = crossEnabled,
        releaseVersionOverride = releaseVersionArg,
        nextVersionOverride = nextVersionArg,
        decisionDefaults = DecisionDefaultsFromPlugin.resolveFromCoreCli(
          cleanState,
          args,
          warnOnDuplicates
        ),
        commandName = runtime.commandName
      )
    )
  }

  private def runPlannedRelease[T](
      inputs: CoreCommandInputs,
      runtime: CommandRuntime[T]
  ): IO[State] =
    for {
      finalCtx <- runtime.resource.use { resourceValue =>
                    for {
                      runProcess  <- resolveReleaseRun(inputs.cleanState, resourceValue, runtime)
                      _           <- IO.blocking(
                                       logReleaseStart(
                                         inputs.cleanState,
                                         runProcess.steps.length,
                                         inputs.crossEnabled
                                       )
                                     )
                      initialCtx  <- runtime.initialContext(
                                       inputs.cleanState,
                                       inputs.skipTests,
                                       inputs.skipPublish,
                                       inputs.interactive
                                     )
                      seededCtx    = initialCtx.withExecutionState(
                                       CoreExecutionState(inputs.plan)
                                     )
                      preparedCtx <-
                        VcsOps.preparePushReleaseIfNeeded(
                          seededCtx,
                          runProcess.steps,
                          ReleaseLogPrefixes.Core
                        )
                      finalCtx    <- ReleaseComposer.compose(
                                       runProcess.steps,
                                       inputs.crossEnabled
                                     )(preparedCtx)
                    } yield finalCtx
                  }
      result   <- ReleaseCommandRunner.finalizeWithCleanState(
                    finalCtx,
                    cleanState = state => CommandStateSupport.cleanReleaseState(state),
                    prefix = ReleaseLogPrefixes.Core
                  )
    } yield result

  private def runPlannedCheck[T](
      inputs: CoreCommandInputs,
      runtime: CommandRuntime[T]
  ): IO[State] =
    for {
      process <- resolveProcessMode(inputs.cleanState, runtime)
      _       <- CheckModeOutput.logCheckStart(
                   inputs.cleanState,
                   ReleaseLogPrefixes.Core,
                   process.steps.length
                 )
      summary <- runtime
                   .initialContext(
                     inputs.cleanState,
                     inputs.skipTests,
                     inputs.skipPublish,
                     inputs.interactive
                   )
                   .flatMap { initialCtx =>
                     CorePreflight.check(
                       initialCtx.withExecutionState(CoreExecutionState(inputs.plan)),
                       process.steps,
                       inputs.crossEnabled
                     )
                   }
      _       <- logLines(inputs.cleanState, CorePreflight.renderSummary(summary))
      _       <- CheckModeOutput.logCheckPassed(inputs.cleanState, ReleaseLogPrefixes.Core)
    } yield inputs.cleanState

  private def logLines(state: State, lines: Seq[String]): IO[Unit] =
    ReleaseCommandRunner.logLines(state, ReleaseLogPrefixes.Core, lines)

  private def logReleaseStart(
      state: State,
      stepCount: Int,
      crossEnabled: Boolean
  ): Unit = {
    state.log.info(s"${ReleaseLogPrefixes.Core} Starting release process...")
    state.log.info(s"${ReleaseLogPrefixes.Core} $stepCount steps to execute")
    if (crossEnabled)
      state.log.info(s"${ReleaseLogPrefixes.Core} Cross-build enabled")
  }

  private[core] def resolveDecisionDefaults(
      state: State,
      args: Seq[ReleaseCli.Arg],
      warnOnDuplicates: Boolean
  ): ReleaseDecisionDefaults =
    DecisionDefaultsFromPlugin.resolveFromCoreCli(state, args, warnOnDuplicates)
}
