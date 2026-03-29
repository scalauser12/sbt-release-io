package io.release.monorepo

import cats.effect.IO
import cats.effect.Resource
import io.release.ReleaseKeys
import io.release.internal.CheckModeOutput
import io.release.internal.ExecutionFlags
import io.release.internal.ReleaseCommandRunner
import io.release.internal.ReleaseLogPrefixes
import io.release.monorepo.steps.MonorepoReleaseSteps
import sbt.{internal as _, *}

import scala.annotation.nowarn

/** Internal runtime helpers for monorepo command planning and execution.
  *
  * Public plugin extension points stay on [[MonorepoReleasePluginLike]]; this object owns the
  * private command plumbing so the plugin trait can read top-down.
  */
private[monorepo] object MonorepoCommandExecution {

  final case class CommandRuntime[T](
      commandName: String,
      resource: Resource[IO, T],
      resolveResourceHooks: State => MonorepoResourceHooks[T],
      resolveReleaseProcess: State => Seq[T => MonorepoStepIO],
      resolveCheckProcess: State => Seq[MonorepoStepIO],
      liftSteps: Seq[MonorepoStepIO] => Seq[T => MonorepoStepIO]
  )

  final case class ReleaseFlags(
      useDefaults: Boolean,
      skipTests: Boolean,
      crossBuild: Boolean,
      allChanged: Boolean,
      skipPublish: Boolean,
      interactive: Boolean
  )

  final case class PlannedCommand(
      cleanState: State,
      flags: ReleaseFlags,
      plan: MonorepoReleasePlan
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
      releaseSteps: Seq[T => MonorepoStepIO],
      checkSteps: Seq[MonorepoStepIO],
      checkLegacy: LegacyStatus,
      releaseLegacy: LegacyStatus
  )

  final case class ResolvedReleaseRun(
      steps: Seq[MonorepoStepIO],
      legacyMode: Boolean,
      legacyReasons: Seq[String]
  ) extends LegacyResult

  private val ReleaseProcessLegacyReason =
    "`monorepoReleaseProcess` differs from the configured raw process"

  def doHelp(state: State, commandName: String): State = {
    val program = logLines(state, MonorepoPreflight.helpLines(commandName))
    ReleaseCommandRunner.runSync(state, ReleaseLogPrefixes.Monorepo)(program.as(state))
  }

  def doRelease[T](
      state: State,
      args: Seq[MonorepoCli.Arg],
      runtime: CommandRuntime[T]
  ): State =
    runMonorepoCommand(state, args, runtime) { (command, session) =>
      runPlannedRelease(command, session, runtime)
    }

  def doCheck[T](
      state: State,
      args: Seq[MonorepoCli.Arg],
      runtime: CommandRuntime[T]
  ): State =
    runMonorepoCommand(state, args, runtime) { (command, session) =>
      runPlannedCheck(command, session, runtime)
    }

  @nowarn("cat=deprecation")
  def resolveProcessMode[T](
      state: State,
      runtime: CommandRuntime[T]
  ): IO[ResolvedProcessMode[T]] =
    IO.blocking {
      val extracted             = Project.extract(state)
      val configuredRaw         = extracted.get(MonorepoReleaseIO._releaseIOMonorepoProcess)
      val configuredCheck       = runtime.resolveCheckProcess(state)
      val configuredRelease     = runtime.resolveReleaseProcess(state)
      val rawProcessChanged     = configuredRaw != MonorepoReleaseSteps.defaults
      val checkProcessChanged   = configuredCheck != configuredRaw
      val checkLegacy           = legacyStatus(
        whenTrue(rawProcessChanged, "`releaseIOMonorepoProcess` differs from defaults"),
        whenTrue(
          checkProcessChanged,
          "`monorepoReleaseCheckProcess` differs from the configured raw process"
        )
      )
      val releaseProcessChanged =
        configuredRelease.length != configuredRaw.length
      val releaseLegacy         = legacyStatus(
        whenTrue(rawProcessChanged, "`releaseIOMonorepoProcess` differs from defaults"),
        whenTrue(
          releaseProcessChanged,
          "`monorepoReleaseProcess` differs from the configured raw process"
        )
      )
      val compiledCheckSteps    =
        if (checkLegacy.legacyMode) configuredCheck
        else
          MonorepoHookCompiler.compile(
            MonorepoHookCompiler
              .resolve(state)
              .mergeWith(
                MonorepoResourceHooks.materialize(runtime.resolveResourceHooks(state), None)
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
        val configuredRaw     = extracted.get(MonorepoReleaseIO._releaseIOMonorepoProcess)
        val configuredRelease = runtime.resolveReleaseProcess(state).map(_(resourceValue))
        val releaseChanged    = configuredRelease != configuredRaw

        if (releaseChanged)
          ResolvedReleaseRun(
            steps = configuredRelease,
            legacyMode = true,
            legacyReasons = Seq(ReleaseProcessLegacyReason)
          )
        else
          ResolvedReleaseRun(
            steps = MonorepoHookCompiler.compile(
              MonorepoHookCompiler
                .resolve(state)
                .mergeWith(
                  MonorepoResourceHooks
                    .materialize(runtime.resolveResourceHooks(state), Some(resourceValue))
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
          s"${ReleaseLogPrefixes.Monorepo} Legacy raw process mode enabled: $reasons"
        )
        state.log.warn(
          s"${ReleaseLogPrefixes.Monorepo} Prefer `releaseIOMonorepoEnable*` policies and " +
            "`releaseIOMonorepo*Hooks` settings. See docs/monorepo/customization.md#hook-based-customization."
        )
        state.log.warn(
          s"${ReleaseLogPrefixes.Monorepo} Hook/policy compilation is bypassed while legacy raw process mode is active."
        )
      }

  private def parseFlags(args: Seq[MonorepoCli.Arg], extracted: Extracted): ReleaseFlags = {
    import MonorepoCli.Arg.*

    ReleaseFlags(
      useDefaults = args.contains(WithDefaults),
      skipTests =
        args.contains(SkipTests) || extracted.get(MonorepoReleaseIO.releaseIOMonorepoSkipTests),
      crossBuild =
        args.contains(CrossBuild) || extracted.get(MonorepoReleaseIO.releaseIOMonorepoCrossBuild),
      allChanged = args.contains(AllChanged),
      skipPublish = extracted.get(MonorepoReleaseIO.releaseIOMonorepoSkipPublish),
      interactive = extracted.get(MonorepoReleaseIO.releaseIOMonorepoInteractive)
    )
  }

  private def plannerInputs(
      args: Seq[MonorepoCli.Arg],
      flags: ReleaseFlags,
      commandName: String
  ): MonorepoReleasePlan.Inputs = {
    import MonorepoCli.Arg.*

    MonorepoReleasePlan.Inputs(
      flags = ExecutionFlags(
        useDefaults = flags.useDefaults,
        skipTests = flags.skipTests,
        skipPublish = flags.skipPublish,
        interactive = flags.interactive,
        crossBuild = flags.crossBuild
      ),
      allChanged = flags.allChanged,
      selectedNames = args.collect { case SelectProject(name) => name },
      releaseVersionPairs = args.collect { case ReleaseVersion(project, version) =>
        project -> version
      },
      nextVersionPairs = args.collect { case NextVersion(project, version) =>
        project -> version
      },
      commandName = commandName
    )
  }

  private def prepareCommand(
      cleanState: State,
      args: Seq[MonorepoCli.Arg],
      commandName: String
  ): IO[Either[State, PlannedCommand]] = {
    val extracted = Project.extract(cleanState)
    val flags     = parseFlags(args, extracted)

    MonorepoReleasePlan
      .build(cleanState, plannerInputs(args, flags, commandName))
      .map(_.map(plan => PlannedCommand(cleanState, flags, plan)))
  }

  private def runMonorepoCommand[T](
      state: State,
      args: Seq[MonorepoCli.Arg],
      runtime: CommandRuntime[T]
  )(run: (PlannedCommand, MonorepoPreparedSession) => IO[State]): State = {
    val cleanState         = state.remove(ReleaseKeys.versions)
    val program: IO[State] = prepareCommand(cleanState, args, runtime.commandName).flatMap {
      case Left(failedState) => IO.pure(failedState)
      case Right(command)    =>
        MonorepoPreparedSession.prepare(command.cleanState, command.plan).flatMap { session =>
          run(command, session)
        }
    }

    ReleaseCommandRunner.runSync(cleanState, ReleaseLogPrefixes.Monorepo)(program)
  }

  private def runPlannedRelease[T](
      command: PlannedCommand,
      session: MonorepoPreparedSession,
      runtime: CommandRuntime[T]
  ): IO[State] =
    for {
      process  <- resolveProcessMode(session.cleanState, runtime)
      finalCtx <- runtime.resource.use { resourceValue =>
                    for {
                      runProcess <- resolveReleaseRun(
                                      session.cleanState,
                                      process,
                                      resourceValue,
                                      runtime
                                    )
                      _          <- logLegacyModeWarning(session.cleanState, runProcess)
                      _          <- IO.blocking(
                                      logReleaseStart(
                                        session.cleanState,
                                        runProcess.steps.length,
                                        session.context.projects.length,
                                        command.flags
                                      )
                                    )
                      finalCtx   <-
                        MonorepoStepIO.compose(runProcess.steps, command.flags.crossBuild)(
                          session.context
                        )
                    } yield finalCtx
                  }
      result   <- ReleaseCommandRunner
                    .handleReleaseResult(finalCtx, ReleaseLogPrefixes.Monorepo)
    } yield result

  private def runPlannedCheck[T](
      command: PlannedCommand,
      session: MonorepoPreparedSession,
      runtime: CommandRuntime[T]
  ): IO[State] =
    for {
      process <- resolveProcessMode(session.cleanState, runtime)
      _       <- logLegacyModeWarning(session.cleanState, process.checkLegacy)
      _       <- CheckModeOutput.logCheckStart(
                   session.cleanState,
                   ReleaseLogPrefixes.Monorepo,
                   process.checkSteps.length
                 )
      summary <- MonorepoPreflight.check(session, process.checkSteps)
      _       <- logLines(session.cleanState, MonorepoPreflight.renderSummary(summary))
      _       <- CheckModeOutput.logCheckPassed(
                   session.cleanState,
                   ReleaseLogPrefixes.Monorepo
                 )
    } yield command.cleanState

  private def logLines(state: State, lines: Seq[String]): IO[Unit] =
    ReleaseCommandRunner.logLines(state, ReleaseLogPrefixes.Monorepo, lines)

  private def logReleaseStart(
      state: State,
      stepCount: Int,
      projectCount: Int,
      flags: ReleaseFlags
  ): Unit = {
    state.log.info(s"${ReleaseLogPrefixes.Monorepo} Starting monorepo release...")
    state.log.info(s"${ReleaseLogPrefixes.Monorepo} $stepCount steps, $projectCount project(s)")
    if (flags.skipTests) state.log.info(s"${ReleaseLogPrefixes.Monorepo} Tests will be skipped")
    if (flags.skipPublish) state.log.info(s"${ReleaseLogPrefixes.Monorepo} Publish will be skipped")
  }

  private def legacyStatus(reasons: Option[String]*): LegacyStatus = {
    val resolved = reasons.flatten

    LegacyStatus(
      legacyMode = resolved.nonEmpty,
      legacyReasons = resolved
    )
  }

  private def whenTrue(condition: Boolean, value: String): Option[String] =
    if (condition) Some(value) else None
}
