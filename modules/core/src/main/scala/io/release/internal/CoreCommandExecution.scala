package io.release.internal

import cats.effect.IO
import cats.effect.Resource
import io.release.ReleaseContext
import io.release.ReleaseIO
import io.release.ReleaseKeys
import io.release.ReleaseResourceHooks
import io.release.ReleaseStepIO
import io.release.VcsOps
import sbt.{internal as _, *}

/** Internal runtime helpers for core command planning and execution.
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

  final case class ResolvedProcessMode(
      checkSteps: Seq[ReleaseStepIO]
  )

  final case class ResolvedReleaseRun(
      steps: Seq[ReleaseStepIO]
  )

  def doHelp(state: State, commandName: String): State = {
    val program = logLines(state, CorePreflight.helpLines(commandName))
    ReleaseCommandRunner.runSync(state, ReleaseLogPrefixes.Core)(program.as(state))
  }

  def doRelease[T](
      state: State,
      args: Seq[ReleaseCli.Arg],
      runtime: CommandRuntime[T]
  ): State = {
    val cleanState = ReleaseIO.clearReleaseManifestMetadata(state.remove(ReleaseKeys.versions))
    val inputs     = buildCommandInputs(
      cleanState,
      args,
      warnOnDuplicates = true,
      interactiveEnabled = runtime.resolveInteractiveEnabled(state),
      runtime
    )
    val program = runPlannedRelease(inputs, runtime)

    ReleaseCommandRunner.runSync(inputs.cleanState, ReleaseLogPrefixes.Core)(program)
  }

  def doCheck[T](
      state: State,
      args: Seq[ReleaseCli.Arg],
      runtime: CommandRuntime[T]
  ): State = {
    val cleanState = ReleaseIO.clearReleaseManifestMetadata(state.remove(ReleaseKeys.versions))
    val inputs     = buildCommandInputs(
      cleanState,
      args,
      warnOnDuplicates = false,
      interactiveEnabled = false,
      runtime
    )
    val program = runPlannedCheck(inputs, runtime)

    ReleaseCommandRunner.runSync(inputs.cleanState, ReleaseLogPrefixes.Core)(program)
  }

  def resolveProcessMode[T](
      state: State,
      runtime: CommandRuntime[T]
  ): IO[ResolvedProcessMode] =
    IO.blocking(
      ResolvedProcessMode(
        checkSteps = ReleaseHookCompiler.compile(
          mergeHookConfiguration(
            ReleaseHookCompiler.resolve(state),
            runtime.resolveResourceHooks(state),
            maybeResource = None
          )
        )
      )
    )

  def resolveReleaseRun[T](
      state: State,
      resourceValue: T,
      runtime: CommandRuntime[T]
  ): IO[ResolvedReleaseRun] =
    IO.blocking(
      ResolvedReleaseRun(
        steps = ReleaseHookCompiler.compile(
          mergeHookConfiguration(
            ReleaseHookCompiler.resolve(state),
            runtime.resolveResourceHooks(state),
            maybeResource = Some(resourceValue)
          )
        )
      )
    )

  private def buildCommandInputs[T](
      cleanState: State,
      args: Seq[ReleaseCli.Arg],
      warnOnDuplicates: Boolean,
      interactiveEnabled: Boolean,
      runtime: CommandRuntime[T]
  ): CoreCommandInputs = {
    import ReleaseCli.Arg.*

    def lastArg[A](extract: PartialFunction[ReleaseCli.Arg, A]): Option[A] =
      args.collect(extract).lastOption

    def allArgs[A](extract: PartialFunction[ReleaseCli.Arg, A]): Seq[A] =
      args.collect(extract)

    val useDefaults   = args.contains(WithDefaults)
    val skipTests     = args.contains(SkipTests)
    val crossFromArgs = args.contains(CrossBuild)
    val crossEnabled  = runtime.resolveCrossBuildEnabled(cleanState) || crossFromArgs
    val skipPublish   = runtime.resolveSkipPublishEnabled(cleanState)
    val interactive   = interactiveEnabled

    val releaseVersionMatches = allArgs { case ReleaseVersion(value) => value }
    val nextVersionMatches    = allArgs { case NextVersion(value) => value }
    val tagDefaultMatches     = allArgs { case TagDefault(value) => value }
    val snapshotMatches       = allArgs { case SnapshotDependenciesDefault(value) => value }
    val remoteMatches         = allArgs { case RemoteCheckFailureDefault(value) => value }
    val upstreamMatches       = allArgs { case UpstreamBehindDefault(value) => value }
    val pushMatches           = allArgs { case PushDefault(value) => value }

    val releaseVersionArg = releaseVersionMatches.lastOption
    val nextVersionArg    = nextVersionMatches.lastOption
    val tagDefaultArg     = tagDefaultMatches.lastOption
    val snapshotDefault   = snapshotMatches.lastOption
    val remoteDefault     = remoteMatches.lastOption
    val upstreamDefault   = upstreamMatches.lastOption
    val pushDefault       = pushMatches.lastOption

    def warnIfRepeated(
        argName: String,
        selected: Option[String],
        count: Int
    ): Unit =
      if (warnOnDuplicates && count > 1)
        cleanState.log.warn(
          s"${ReleaseLogPrefixes.Core} Multiple $argName args provided; using '${selected.getOrElse("<unknown>")}'"
        )

    warnIfRepeated("release-version", releaseVersionArg, releaseVersionMatches.size)
    warnIfRepeated("next-version", nextVersionArg, nextVersionMatches.size)
    warnIfRepeated("default-tag-exists-answer", tagDefaultArg, tagDefaultMatches.size)
    warnIfRepeated(
      "default-snapshot-dependencies-answer",
      snapshotDefault.map(renderYesNo),
      snapshotMatches.size
    )
    warnIfRepeated(
      "default-remote-check-failure-answer",
      remoteDefault.map(renderYesNo),
      remoteMatches.size
    )
    warnIfRepeated(
      "default-upstream-behind-answer",
      upstreamDefault.map(renderYesNo),
      upstreamMatches.size
    )
    warnIfRepeated(
      "default-push-answer",
      pushDefault.map(renderYesNo),
      pushMatches.size
    )

    val settings    = ReleaseDecisionDefaults.fromState(cleanState)
    val cliDefaults = ReleaseDecisionDefaults(
      tagExistsAnswer = tagDefaultArg,
      snapshotDependenciesAnswer = snapshotDefault,
      remoteCheckFailureAnswer = remoteDefault,
      upstreamBehindAnswer = upstreamDefault,
      pushAnswer = pushDefault
    )

    CoreCommandInputs(
      cleanState = cleanState,
      skipTests = skipTests,
      skipPublish = skipPublish,
      interactive = interactive,
      crossEnabled = crossEnabled,
      plan = CoreReleasePlan.build(
        CoreReleasePlan.Inputs(
          useDefaults = useDefaults,
          skipTests = skipTests,
          skipPublish = skipPublish,
          interactive = interactive,
          crossBuild = crossEnabled,
          releaseVersionOverride = releaseVersionArg,
          nextVersionOverride = nextVersionArg,
          decisionDefaults = ReleaseDecisionDefaults.merge(cliDefaults, settings),
          commandName = runtime.commandName
        )
      )
    )
  }

  private def renderYesNo(value: Boolean): String =
    if (value) "y" else "n"

  private def runPlannedRelease[T](
      inputs: CoreCommandInputs,
      runtime: CommandRuntime[T]
  ): IO[State] =
    for {
      process  <- resolveProcessMode(inputs.cleanState, runtime)
      finalCtx <- runtime.resource.use { resourceValue =>
                    for {
                      runProcess <- resolveReleaseRun(inputs.cleanState, resourceValue, runtime)
                      _          <- IO.blocking(
                                      logReleaseStart(
                                        inputs.cleanState,
                                        runProcess.steps.length,
                                        inputs.crossEnabled
                                      )
                                    )
                      initialCtx <- runtime.initialContext(
                                      inputs.cleanState,
                                      inputs.skipTests,
                                      inputs.skipPublish,
                                      inputs.interactive
                                    )
                      seededCtx    = initialCtx.withExecutionState(
                                       CoreExecutionState(inputs.plan)
                                     )
                      preparedCtx <-
                        if (runProcess.steps.exists(_.name == VcsOps.PushChangesStepName))
                          VcsOps.preparePushRelease(
                            seededCtx,
                            ReleaseLogPrefixes.Core,
                            remoteCheckLog = Some(r =>
                              seededCtx.state.log.info(
                                s"${ReleaseLogPrefixes.Core} Checking remote [$r] before release actions ..."
                              )
                            )
                          )
                        else IO.pure(seededCtx)
                      finalCtx   <- ReleaseStepIO.compose(
                                      runProcess.steps,
                                      inputs.crossEnabled
                                    )(preparedCtx)
                    } yield finalCtx
                  }
      cleanedCtx <- IO.blocking(
                      finalCtx.withState(
                        ReleaseIO.clearReleaseManifestMetadata(finalCtx.state)
                      )
                    )
      result     <- ReleaseCommandRunner
                      .handleReleaseResult(cleanedCtx, ReleaseLogPrefixes.Core)
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
                   process.checkSteps.length
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
                       process.checkSteps,
                       inputs.crossEnabled
                     )
                   }
      _       <- logLines(inputs.cleanState, CorePreflight.renderSummary(summary))
      _       <- CheckModeOutput.logCheckPassed(inputs.cleanState, ReleaseLogPrefixes.Core)
    } yield inputs.cleanState

  private def mergeHookConfiguration[T](
      plainHooks: CoreHookConfiguration,
      resourceHooks: ReleaseResourceHooks[T],
      maybeResource: Option[T]
  ): CoreHookConfiguration =
    plainHooks.mergeWith(ReleaseResourceHooks.materialize(resourceHooks, maybeResource))

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
