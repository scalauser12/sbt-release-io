package io.release.internal

import cats.effect.IO
import cats.effect.Resource
import io.release.ReleaseContext
import io.release.ReleaseIO
import io.release.ReleaseKeys
import io.release.ReleaseResourceHooks
import io.release.ReleaseStepIO
import sbt.{internal as _, *}

import scala.annotation.nowarn

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
      resolveReleaseProcess: State => Seq[T => ReleaseStepIO],
      resolveCheckProcess: State => Seq[ReleaseStepIO],
      resolveCrossBuildEnabled: State => Boolean,
      resolveSkipPublishEnabled: State => Boolean,
      resolveInteractiveEnabled: State => Boolean,
      initialContext: (State, Boolean, Boolean, Boolean) => IO[ReleaseContext],
      liftSteps: Seq[ReleaseStepIO] => Seq[T => ReleaseStepIO]
  )

  final case class CoreCommandInputs(
      cleanState: State,
      skipTests: Boolean,
      skipPublish: Boolean,
      interactive: Boolean,
      crossEnabled: Boolean,
      plan: CoreReleasePlan
  )

  sealed trait LegacyResult {
    def legacyMode: Boolean
    def legacyReasons: Seq[String]
  }

  final case class LegacyStatus(
      legacyMode: Boolean,
      legacyReasons: Seq[String]
  ) extends LegacyResult

  final case class ResolvedProcessMode[T](
      releaseSteps: Seq[T => ReleaseStepIO],
      checkSteps: Seq[ReleaseStepIO],
      checkLegacy: LegacyStatus,
      releaseLegacy: LegacyStatus
  )

  final case class ResolvedReleaseRun(
      steps: Seq[ReleaseStepIO],
      legacyMode: Boolean,
      legacyReasons: Seq[String]
  ) extends LegacyResult

  private val ReleaseProcessLegacyReason =
    "`releaseProcess` differs from the configured raw process"

  def doHelp(state: State, commandName: String): State = {
    val program = logLines(state, CorePreflight.helpLines(commandName))
    ReleaseCommandRunner.runSync(state, ReleaseLogPrefixes.Core)(program.as(state))
  }

  def doRelease[T](
      state: State,
      args: Seq[ReleaseCli.Arg],
      runtime: CommandRuntime[T]
  ): State = {
    val inputs  = buildCommandInputs(state, args, warnOnDuplicates = true, runtime)
    val program = runPlannedRelease(inputs, runtime)

    ReleaseCommandRunner.runSync(inputs.cleanState, ReleaseLogPrefixes.Core)(program)
  }

  def doCheck[T](
      state: State,
      args: Seq[ReleaseCli.Arg],
      runtime: CommandRuntime[T]
  ): State = {
    val inputs  = buildCommandInputs(state, args, warnOnDuplicates = false, runtime)
    val program = runPlannedCheck(inputs, runtime)

    ReleaseCommandRunner.runSync(inputs.cleanState, ReleaseLogPrefixes.Core)(program)
  }

  @nowarn("cat=deprecation")
  def resolveProcessMode[T](
      state: State,
      runtime: CommandRuntime[T]
  ): IO[ResolvedProcessMode[T]] =
    IO.blocking {
      val extracted             = Project.extract(state)
      val configuredRaw         = extracted.get(ReleaseIO._releaseIOProcess)
      val configuredCheck       = runtime.resolveCheckProcess(state)
      val configuredRelease     = runtime.resolveReleaseProcess(state)
      val rawSignature          = CoreLifecycle.signature(configuredRaw)
      val checkSignature        = CoreLifecycle.signature(configuredCheck)
      val rawReleaseSignature   = CoreLifecycle.releaseBuilderSignature(
        runtime.liftSteps(configuredRaw)
      )
      val releaseSignature      = CoreLifecycle.releaseBuilderSignature(configuredRelease)
      val rawProcessChanged     = rawSignature != CoreLifecycle.defaultSignature
      val checkProcessChanged   = checkSignature != rawSignature
      val checkLegacy           = legacyStatus(
        whenTrue(rawProcessChanged, "`releaseIOProcess` differs from defaults"),
        whenTrue(
          checkProcessChanged,
          "`releaseCheckProcess` differs from the configured raw process"
        )
      )
      val releaseProcessChanged = releaseSignature != rawReleaseSignature
      val releaseLegacy         = legacyStatus(
        whenTrue(rawProcessChanged, "`releaseIOProcess` differs from defaults"),
        whenTrue(
          releaseProcessChanged,
          "`releaseProcess` differs from the configured raw process"
        )
      )
      val compiledCheckSteps    =
        if (checkLegacy.legacyMode) configuredCheck
        else
          ReleaseHookCompiler.compile(
            mergeHookConfiguration(
              ReleaseHookCompiler.resolve(state),
              runtime.resolveResourceHooks(state),
              maybeResource = None
            )
          )

      ResolvedProcessMode(
        releaseSteps = configuredRelease,
        checkSteps = compiledCheckSteps,
        checkLegacy = checkLegacy,
        releaseLegacy = releaseLegacy
      )
    }

  @nowarn("cat=deprecation")
  def resolveReleaseRun[T](
      state: State,
      processMode: ResolvedProcessMode[T],
      resourceValue: T,
      runtime: CommandRuntime[T]
  ): IO[ResolvedReleaseRun] =
    if (processMode.releaseLegacy.legacyMode)
      IO.pure(
        ResolvedReleaseRun(
          steps = processMode.releaseSteps.map(_(resourceValue)),
          legacyMode = true,
          legacyReasons = processMode.releaseLegacy.legacyReasons
        )
      )
    else
      IO.blocking {
        val extracted         = Project.extract(state)
        val configuredRaw     = extracted.get(ReleaseIO._releaseIOProcess)
        val configuredRelease = runtime.resolveReleaseProcess(state).map(_(resourceValue))
        val releaseChanged    =
          CoreLifecycle.signature(configuredRelease) != CoreLifecycle.signature(configuredRaw)

        if (releaseChanged)
          ResolvedReleaseRun(
            steps = configuredRelease,
            legacyMode = true,
            legacyReasons = Seq(ReleaseProcessLegacyReason)
          )
        else
          ResolvedReleaseRun(
            steps = ReleaseHookCompiler.compile(
              mergeHookConfiguration(
                ReleaseHookCompiler.resolve(state),
                runtime.resolveResourceHooks(state),
                maybeResource = Some(resourceValue)
              )
            ),
            legacyMode = false,
            legacyReasons = Seq.empty
          )
      }

  def logLegacyModeWarning(state: State, result: LegacyResult): IO[Unit] =
    if (!result.legacyMode) IO.unit
    else
      IO.blocking {
        val reasons = result.legacyReasons.mkString("; ")
        state.log.warn(
          s"${ReleaseLogPrefixes.Core} Legacy raw process mode enabled: $reasons"
        )
        state.log.warn(
          s"${ReleaseLogPrefixes.Core} Prefer `releaseIOEnable*` policies and `releaseIO*Hooks` settings. " +
            "See docs/core/customization.md#hook-based-customization."
        )
        state.log.warn(
          s"${ReleaseLogPrefixes.Core} Hook/policy compilation is bypassed while legacy raw process mode is active."
        )
      }

  private def buildCommandInputs[T](
      state: State,
      args: Seq[ReleaseCli.Arg],
      warnOnDuplicates: Boolean,
      runtime: CommandRuntime[T]
  ): CoreCommandInputs = {
    import ReleaseCli.Arg.*

    val useDefaults   = args.contains(WithDefaults)
    val skipTests     = args.contains(SkipTests)
    val crossFromArgs = args.contains(CrossBuild)
    val crossEnabled  = runtime.resolveCrossBuildEnabled(state) || crossFromArgs
    val skipPublish   = runtime.resolveSkipPublishEnabled(state)
    val interactive   = runtime.resolveInteractiveEnabled(state)

    val releaseVersionArg = args.collectFirst { case ReleaseVersion(value) => value }
    val nextVersionArg    = args.collectFirst { case NextVersion(value) => value }
    val tagDefaultArg     = args.collectFirst { case TagDefault(value) => value }

    def warnIfRepeated(
        argName: String,
        selected: Option[String],
        matches: ReleaseCli.Arg => Boolean
    ): Unit =
      if (warnOnDuplicates && args.count(matches) > 1)
        state.log.warn(
          s"${ReleaseLogPrefixes.Core} Multiple $argName args provided; using '${selected.getOrElse("<unknown>")}'"
        )

    warnIfRepeated(
      "release-version",
      releaseVersionArg,
      {
        case ReleaseVersion(_) => true
        case _                 => false
      }
    )
    warnIfRepeated(
      "next-version",
      nextVersionArg,
      {
        case NextVersion(_) => true
        case _              => false
      }
    )
    warnIfRepeated(
      "default-tag-exists-answer",
      tagDefaultArg,
      {
        case TagDefault(_) => true
        case _             => false
      }
    )

    val cleanState = state.remove(ReleaseKeys.versions)

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
          tagDefault = tagDefaultArg,
          commandName = runtime.commandName
        )
      )
    )
  }

  private def runPlannedRelease[T](
      inputs: CoreCommandInputs,
      runtime: CommandRuntime[T]
  ): IO[State] =
    for {
      process  <- resolveProcessMode(inputs.cleanState, runtime)
      finalCtx <- runtime.resource.use { resourceValue =>
                    for {
                      runProcess <- resolveReleaseRun(
                                      inputs.cleanState,
                                      process,
                                      resourceValue,
                                      runtime
                                    )
                      _          <- logLegacyModeWarning(inputs.cleanState, runProcess)
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
                      finalCtx   <- ReleaseStepIO.compose(
                                      runProcess.steps,
                                      inputs.crossEnabled
                                    )(
                                      initialCtx.withExecutionState(
                                        CoreExecutionState(inputs.plan)
                                      )
                                    )
                    } yield finalCtx
                  }
      result   <- ReleaseCommandRunner
                    .handleReleaseResult(finalCtx, ReleaseLogPrefixes.Core)
    } yield result

  private def runPlannedCheck[T](
      inputs: CoreCommandInputs,
      runtime: CommandRuntime[T]
  ): IO[State] =
    for {
      process <- resolveProcessMode(inputs.cleanState, runtime)
      _       <- logLegacyModeWarning(inputs.cleanState, process.checkLegacy)
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

  private def legacyStatus(reasons: Option[String]*): LegacyStatus = {
    val resolved = reasons.flatten

    LegacyStatus(
      legacyMode = resolved.nonEmpty,
      legacyReasons = resolved
    )
  }

  private def whenTrue(condition: Boolean, value: String): Option[String] =
    if (condition) Some(value) else None

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
