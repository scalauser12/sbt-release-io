package io.release.monorepo

import cats.effect.IO
import cats.effect.Resource
import io.release.ReleaseKeys
import io.release.internal.CheckModeOutput
import io.release.internal.ExecutionFlags
import io.release.internal.ReleaseDecisionDefaults
import io.release.internal.ReleaseCommandRunner
import io.release.internal.ReleaseLogPrefixes
import sbt.{internal as _, *}

/** Internal runtime helpers for monorepo command planning and execution.
  *
  * Public plugin extension points stay on [[MonorepoReleasePluginLike]]; this object owns the
  * private command plumbing so the plugin trait can read top-down.
  */
private[monorepo] object MonorepoCommandExecution {

  final case class CommandRuntime[T](
      commandName: String,
      resource: Resource[IO, T],
      resolveResourceHooks: State => MonorepoResourceHooks[T]
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

  final case class ResolvedProcessMode(
      checkSteps: Seq[MonorepoStepIO]
  )

  final case class ResolvedReleaseRun(
      steps: Seq[MonorepoStepIO]
  )

  def doHelp(state: State, commandName: String): State = {
    val program = logLines(state, MonorepoPreflight.helpLines(commandName))
    ReleaseCommandRunner.runSync(state, ReleaseLogPrefixes.Monorepo)(program.as(state))
  }

  def doRelease[T](
      state: State,
      args: Seq[MonorepoCli.Arg],
      runtime: CommandRuntime[T]
  ): State =
    runMonorepoCommand(state, args, runtime, interactiveEnabled = true) { (command, session) =>
      runPlannedRelease(command, session, runtime)
    }

  def doCheck[T](
      state: State,
      args: Seq[MonorepoCli.Arg],
      runtime: CommandRuntime[T]
  ): State =
    runMonorepoCommand(state, args, runtime, interactiveEnabled = false) { (command, session) =>
      runPlannedCheck(command, session, runtime)
    }

  def resolveProcessMode[T](
      state: State,
      runtime: CommandRuntime[T]
  ): IO[ResolvedProcessMode] =
    IO.blocking(
      ResolvedProcessMode(
        checkSteps = MonorepoHookCompiler.compile(
          MonorepoHookCompiler
            .resolve(state)
            .mergeWith(
              MonorepoResourceHooks.materialize(runtime.resolveResourceHooks(state), None)
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
        steps = MonorepoHookCompiler.compile(
          MonorepoHookCompiler
            .resolve(state)
            .mergeWith(
              MonorepoResourceHooks
                .materialize(runtime.resolveResourceHooks(state), Some(resourceValue))
            )
        )
      )
    )

  private def parseFlags(
      args: Seq[MonorepoCli.Arg],
      extracted: Extracted,
      interactiveEnabled: Boolean
  ): ReleaseFlags = {
    import MonorepoCli.Arg.*

    ReleaseFlags(
      useDefaults = args.contains(WithDefaults),
      skipTests =
        args.contains(SkipTests) || extracted.get(MonorepoReleaseIO.releaseIOMonorepoSkipTests),
      crossBuild =
        args.contains(CrossBuild) || extracted.get(MonorepoReleaseIO.releaseIOMonorepoCrossBuild),
      allChanged = args.contains(AllChanged),
      skipPublish = extracted.get(MonorepoReleaseIO.releaseIOMonorepoSkipPublish),
      interactive =
        interactiveEnabled && extracted.get(MonorepoReleaseIO.releaseIOMonorepoInteractive)
    )
  }

  private def plannerInputs(
      args: Seq[MonorepoCli.Arg],
      flags: ReleaseFlags,
      defaults: ReleaseDecisionDefaults,
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
      decisionDefaults = defaults,
      commandName = commandName
    )
  }

  private def prepareCommand(
      cleanState: State,
      args: Seq[MonorepoCli.Arg],
      interactiveEnabled: Boolean,
      commandName: String
  ): IO[Either[State, PlannedCommand]] = {
    val extracted = Project.extract(cleanState)
    val flags     = parseFlags(args, extracted, interactiveEnabled)
    val defaults  = resolveDecisionDefaults(cleanState, args)

    MonorepoReleasePlan
      .build(cleanState, plannerInputs(args, flags, defaults, commandName))
      .map(_.map(plan => PlannedCommand(cleanState, flags, plan)))
  }

  private def runMonorepoCommand[T](
      state: State,
      args: Seq[MonorepoCli.Arg],
      runtime: CommandRuntime[T],
      interactiveEnabled: Boolean
  )(run: (PlannedCommand, MonorepoPreparedSession) => IO[State]): State = {
    val cleanState         = state.remove(ReleaseKeys.versions)
    val program: IO[State] = prepareCommand(
      cleanState,
      args,
      interactiveEnabled,
      runtime.commandName
    ).flatMap {
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
                      runProcess <- resolveReleaseRun(session.cleanState, resourceValue, runtime)
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

  private def resolveDecisionDefaults(
      state: State,
      args: Seq[MonorepoCli.Arg]
  ): ReleaseDecisionDefaults = {
    import MonorepoCli.Arg.*

    def allArgs[A](extract: PartialFunction[MonorepoCli.Arg, A]): Seq[A] =
      args.collect(extract)

    def warnIfRepeated(
        argName: String,
        selected: Option[String],
        count: Int
    ): Unit =
      if (count > 1)
        state.log.warn(
          s"${ReleaseLogPrefixes.Monorepo} Multiple $argName args provided; using '${selected.getOrElse("<unknown>")}'"
        )

    val tagMatches      = allArgs { case TagDefault(value) => value }
    val snapshotMatches = allArgs { case SnapshotDependenciesDefault(value) => value }
    val remoteMatches   = allArgs { case RemoteCheckFailureDefault(value) => value }
    val upstreamMatches = allArgs { case UpstreamBehindDefault(value) => value }
    val pushMatches     = allArgs { case PushDefault(value) => value }

    val cliDefaults = ReleaseDecisionDefaults(
      tagExistsAnswer = tagMatches.lastOption,
      snapshotDependenciesAnswer = snapshotMatches.lastOption,
      remoteCheckFailureAnswer = remoteMatches.lastOption,
      upstreamBehindAnswer = upstreamMatches.lastOption,
      pushAnswer = pushMatches.lastOption
    )

    warnIfRepeated(
      "default-tag-exists-answer",
      cliDefaults.tagExistsAnswer,
      tagMatches.size
    )
    warnIfRepeated(
      "default-snapshot-dependencies-answer",
      cliDefaults.snapshotDependenciesAnswer.map(renderYesNo),
      snapshotMatches.size
    )
    warnIfRepeated(
      "default-remote-check-failure-answer",
      cliDefaults.remoteCheckFailureAnswer.map(renderYesNo),
      remoteMatches.size
    )
    warnIfRepeated(
      "default-upstream-behind-answer",
      cliDefaults.upstreamBehindAnswer.map(renderYesNo),
      upstreamMatches.size
    )
    warnIfRepeated(
      "default-push-answer",
      cliDefaults.pushAnswer.map(renderYesNo),
      pushMatches.size
    )

    ReleaseDecisionDefaults.merge(cliDefaults, ReleaseDecisionDefaults.fromState(state))
  }

  private def renderYesNo(value: Boolean): String =
    if (value) "y" else "n"
}
