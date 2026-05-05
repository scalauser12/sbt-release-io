package io.release.core.internal

import cats.effect.IO
import cats.effect.Resource
import io.release.ReleaseComposer
import io.release.ReleaseContext
import io.release.ReleaseResourceHooks
import io.release.VcsOps
import io.release.core.internal.CoreStepAliases.Step
import io.release.runtime.ReleaseLogPrefixes
import io.release.runtime.command.CheckModeOutput
import io.release.runtime.command.CommandStateSupport
import io.release.runtime.command.ReleaseCommandRunner
import io.release.runtime.engine.BuiltInStepRole
import io.release.runtime.workflow.DecisionDefaultsSupport
import io.release.runtime.workflow.DecisionResolver
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
      configuredInteractive: Boolean,
      crossEnabled: Boolean,
      plan: CoreReleasePlan
  )

  private val cliExtractors = DecisionDefaultsSupport.CliExtractors[ReleaseCli.Arg](
    tagExists = { case ReleaseCli.Arg.TagDefault(value) => value },
    snapshotDependencies = { case ReleaseCli.Arg.SnapshotDependenciesDefault(value) => value },
    remoteCheckFailure = { case ReleaseCli.Arg.RemoteCheckFailureDefault(value) => value },
    upstreamBehind = { case ReleaseCli.Arg.UpstreamBehindDefault(value) => value },
    push = { case ReleaseCli.Arg.PushDefault(value) => value }
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
    runCommand(
      state,
      args,
      runtime,
      warnOnDuplicates = true,
      resolveInteractive = runtime.resolveInteractiveEnabled,
      run = runPlannedRelease(_, runtime)
    )

  def doCheck[T](
      state: State,
      args: Seq[ReleaseCli.Arg],
      runtime: CommandRuntime[T]
  ): State =
    runCommand(
      state,
      args,
      runtime,
      warnOnDuplicates = true,
      resolveInteractive = _ => false,
      run = runPlannedCheck(_, runtime)
    )

  private def runCommand[T](
      state: State,
      args: Seq[ReleaseCli.Arg],
      runtime: CommandRuntime[T],
      warnOnDuplicates: Boolean,
      resolveInteractive: State => Boolean,
      run: CoreCommandInputs => IO[State]
  ): State =
    ReleaseCommandRunner.runPreparedCommand(
      state = state,
      cleanState = state => CommandStateSupport.cleanReleaseState(state),
      logPrefix = ReleaseLogPrefixes.Core
    )(
      cleanState =>
        IO
          .blocking(
            buildCommandInputs(
              cleanState,
              args,
              warnOnDuplicates = warnOnDuplicates,
              interactiveEnabled = resolveInteractive(cleanState),
              runtime
            )
          )
          .map(Right(_)),
      run
    )

  def resolveProcessMode[T](
      state: State,
      runtime: CommandRuntime[T]
  ): IO[Seq[Step]] =
    compileMergedSteps(state, runtime, maybeResource = None)

  def resolveReleaseRun[T](
      state: State,
      resourceValue: T,
      runtime: CommandRuntime[T]
  ): IO[Seq[Step]] =
    compileMergedSteps(state, runtime, maybeResource = Some(resourceValue))

  private def compileMergedSteps[T](
      state: State,
      runtime: CommandRuntime[T],
      maybeResource: Option[T]
  ): IO[Seq[Step]] =
    for {
      mergedHooks <- IO.blocking {
                       val resolvedHooks     = CoreHookConfiguration.resolve(state)
                       val resourceHooks     = runtime.resolveResourceHooks(state)
                       val materializedHooks =
                         ReleaseResourceHooks.materialize(resourceHooks, maybeResource)
                       resolvedHooks.mergeWith(materializedHooks)
                     }
      steps       <- CoreLifecycle.compile(mergedHooks)
    } yield steps

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
      interactive = interactiveEnabled,
      configuredInteractive = runtime.resolveInteractiveEnabled(cleanState),
      crossEnabled = crossEnabled,
      plan = CoreReleasePlan.fromFlags(
        useDefaults = useDefaults,
        skipTests = skipTests,
        skipPublish = skipPublish,
        interactive = interactiveEnabled,
        crossBuild = crossEnabled,
        releaseVersionOverride = releaseVersionArg,
        nextVersionOverride = nextVersionArg,
        decisionDefaults = DecisionDefaultsSupport.resolveFromArgs(
          cleanState,
          ReleaseLogPrefixes.Core,
          args,
          cliExtractors,
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
                      steps       <- resolveReleaseRun(inputs.cleanState, resourceValue, runtime)
                      _           <- IO.blocking(
                                       logReleaseStart(
                                         inputs.cleanState,
                                         steps.length,
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
                                       CoreExecutionState(
                                         inputs.plan,
                                         pushConfigured =
                                           steps.exists(_.hasRole(BuiltInStepRole.PushChanges))
                                       )
                                     )
                      preparedCtx <-
                        // Skip the early remote warmup whenever the push step is
                        // guaranteed to take its decline branch — explicit
                        // `Some(false)` answer or non-interactive with no
                        // configured choice and no `with-defaults`. Otherwise a
                        // local/no-upstream release would abort here even though
                        // `pushChanges.execute` would later decline cleanly.
                        if (DecisionResolver.effectivelyDeclinedPush(seededCtx))
                          IO.pure(seededCtx)
                        else
                          VcsOps.preparePushReleaseIfNeeded(
                            seededCtx,
                            steps,
                            ReleaseLogPrefixes.Core
                          )
                      finalCtx    <- ReleaseComposer.compose(
                                       steps,
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
      steps   <- resolveProcessMode(inputs.cleanState, runtime)
      _       <- CheckModeOutput.logCheckStart(
                   inputs.cleanState,
                   ReleaseLogPrefixes.Core,
                   steps.length
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
                       initialCtx.withExecutionState(
                         CoreExecutionState(
                           inputs.plan,
                           pushConfigured = steps.exists(_.hasRole(BuiltInStepRole.PushChanges))
                         )
                       ),
                       steps,
                       inputs.crossEnabled,
                       tagPreflightInteractive = inputs.configuredInteractive
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
}
