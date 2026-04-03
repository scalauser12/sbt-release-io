package io.release.monorepo

import cats.effect.IO
import cats.effect.Resource
import io.release.internal.CheckModeOutput
import io.release.internal.CommandRuntimeSupport
import io.release.internal.DecisionDefaultsSupport
import io.release.internal.ExecutionFlags
import io.release.internal.ReleaseDecisionDefaults
import io.release.internal.ReleaseCommandRunner
import io.release.internal.ReleaseLogPrefixes
import io.release.internal.SbtRuntime
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
      resolveResourceHooks: State => MonorepoResourceHooks[T],
      resolveCrossBuildEnabled: State => Boolean,
      resolveSkipTestsEnabled: State => Boolean,
      resolveSkipPublishEnabled: State => Boolean,
      resolveInteractiveEnabled: State => Boolean
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

  final case class CompiledMonorepoSteps(
      steps: Seq[MonorepoProcessStep]
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
    runMonorepoCommand(
      state,
      args,
      runtime,
      interactiveEnabled = true,
      warnOnDuplicates = true
    ) { (command, session) =>
      runPlannedRelease(command, session, runtime)
    }

  def doCheck[T](
      state: State,
      args: Seq[MonorepoCli.Arg],
      runtime: CommandRuntime[T]
  ): State =
    runMonorepoCommand(
      state,
      args,
      runtime,
      interactiveEnabled = false,
      warnOnDuplicates = false
    ) { (command, session) =>
      runPlannedCheck(command, session, runtime)
    }

  def resolveProcessMode[T](
      state: State,
      runtime: CommandRuntime[T]
  ): IO[CompiledMonorepoSteps] =
    compileMergedSteps(state, runtime, maybeResource = None)

  def resolveReleaseRun[T](
      state: State,
      resourceValue: T,
      runtime: CommandRuntime[T]
  ): IO[CompiledMonorepoSteps] =
    compileMergedSteps(state, runtime, maybeResource = Some(resourceValue))

  private def compileMergedSteps[T](
      state: State,
      runtime: CommandRuntime[T],
      maybeResource: Option[T]
  ): IO[CompiledMonorepoSteps] =
    IO.blocking(
      CompiledMonorepoSteps(
        steps = MonorepoHookCompiler.compile(
          CommandRuntimeSupport.mergeMaterializedHooks(
            MonorepoHookCompiler.resolve(state),
            runtime.resolveResourceHooks(state),
            maybeResource
          )(MonorepoResourceHooks.materialize, (left, right) => left.mergeWith(right))
        )
      )
    )

  private[monorepo] def resolveFlags[T](
      cleanState: State,
      args: Seq[MonorepoCli.Arg],
      runtime: CommandRuntime[T],
      interactiveEnabled: Boolean
  ): ReleaseFlags = {
    import MonorepoCli.Arg.*

    ReleaseFlags(
      useDefaults = args.contains(WithDefaults),
      skipTests = args.contains(SkipTests) || runtime.resolveSkipTestsEnabled(cleanState),
      crossBuild = args.contains(CrossBuild) || runtime.resolveCrossBuildEnabled(cleanState),
      allChanged = args.contains(AllChanged),
      skipPublish = runtime.resolveSkipPublishEnabled(cleanState),
      interactive = interactiveEnabled && runtime.resolveInteractiveEnabled(cleanState)
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

  private def prepareCommand[T](
      cleanState: State,
      args: Seq[MonorepoCli.Arg],
      runtime: CommandRuntime[T],
      interactiveEnabled: Boolean,
      warnOnDuplicates: Boolean
  ): IO[Either[State, PlannedCommand]] =
    for {
      flags   <- IO.blocking(resolveFlags(cleanState, args, runtime, interactiveEnabled))
      defaults = resolveDecisionDefaults(cleanState, args, warnOnDuplicates)
      planned <- MonorepoReleasePlan.build(
                   cleanState,
                   plannerInputs(args, flags, defaults, runtime.commandName)
                 )
    } yield planned.map(plan => PlannedCommand(cleanState, flags, plan))

  private def runMonorepoCommand[T](
      state: State,
      args: Seq[MonorepoCli.Arg],
      runtime: CommandRuntime[T],
      interactiveEnabled: Boolean,
      warnOnDuplicates: Boolean
  )(run: (PlannedCommand, MonorepoPreparedSession) => IO[State]): State = {
    val cleanState         = CommandRuntimeSupport.cleanReleaseState(state, loadedProjectRefs(state))
    val program: IO[State] = prepareCommand(
      cleanState,
      args,
      runtime,
      interactiveEnabled,
      warnOnDuplicates
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
      finalCtx   <- runtime.resource.use { resourceValue =>
                      for {
                        runProcess  <- resolveReleaseRun(session.cleanState, resourceValue, runtime)
                        _           <- IO.blocking(
                                         logReleaseStart(
                                           session.cleanState,
                                           runProcess.steps.length,
                                           session.context.projects.length,
                                           command.flags
                                         )
                                       )
                        preparedCtx <-
                          CommandRuntimeSupport.preparePushIfNeeded(
                            session.context,
                            runProcess.steps.map(_.name),
                            ReleaseLogPrefixes.Monorepo
                          )
                        finalCtx    <-
                          MonorepoProcessStep.compose(runProcess.steps, command.flags.crossBuild)(
                            preparedCtx
                          )
                      } yield finalCtx
                    }
      cleanedCtx <- IO.blocking(
                      finalCtx.withState(
                        CommandRuntimeSupport.cleanReleaseState(
                          finalCtx.state,
                          loadedProjectRefs(finalCtx.state)
                        )
                      )
                    )
      result     <- ReleaseCommandRunner
                      .handleReleaseResult(cleanedCtx, ReleaseLogPrefixes.Monorepo)
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
                   process.steps.length
                 )
      summary <- MonorepoPreflight.check(session, process.steps)
      _       <- logLines(session.cleanState, MonorepoPreflight.renderSummary(summary))
      _       <- CheckModeOutput.logCheckPassed(
                   session.cleanState,
                   ReleaseLogPrefixes.Monorepo
                 )
    } yield command.cleanState

  private def logLines(state: State, lines: Seq[String]): IO[Unit] =
    CommandRuntimeSupport.logLines(state, ReleaseLogPrefixes.Monorepo, lines)

  private def loadedProjectRefs(state: State): Seq[ProjectRef] =
    SbtRuntime.extracted(state).structure.allProjectRefs

  private[monorepo] def releaseStartLines(
      stepCount: Int,
      projectCount: Int,
      flags: ReleaseFlags
  ): List[String] = {
    val prefix = ReleaseLogPrefixes.Monorepo

    List(
      s"$prefix Starting monorepo release...",
      s"$prefix $stepCount steps, $projectCount project(s)"
    ) ++
      (if (flags.crossBuild) List(s"$prefix Cross-build enabled") else Nil) ++
      (if (flags.skipTests) List(s"$prefix Tests will be skipped") else Nil) ++
      (if (flags.skipPublish) List(s"$prefix Publish will be skipped") else Nil)
  }

  private def logReleaseStart(
      state: State,
      stepCount: Int,
      projectCount: Int,
      flags: ReleaseFlags
  ): Unit =
    releaseStartLines(stepCount, projectCount, flags).foreach(line => state.log.info(line))

  private[monorepo] def resolveDecisionDefaults(
      state: State,
      args: Seq[MonorepoCli.Arg],
      warnOnDuplicates: Boolean
  ): ReleaseDecisionDefaults = {
    import MonorepoCli.Arg.*

    def allArgs[A](extract: PartialFunction[MonorepoCli.Arg, A]): Seq[A] =
      args.collect(extract)

    val cliDefaults = ReleaseDecisionDefaults(
      tagExistsAnswer = DecisionDefaultsSupport.resolveLast(
        state,
        ReleaseLogPrefixes.Monorepo,
        "default-tag-exists-answer",
        allArgs { case TagDefault(value) => value },
        (value: String) => value,
        warnOnDuplicates
      ),
      snapshotDependenciesAnswer = DecisionDefaultsSupport.resolveLast(
        state,
        ReleaseLogPrefixes.Monorepo,
        "default-snapshot-dependencies-answer",
        allArgs { case SnapshotDependenciesDefault(value) => value },
        DecisionDefaultsSupport.renderYesNo,
        warnOnDuplicates
      ),
      remoteCheckFailureAnswer = DecisionDefaultsSupport.resolveLast(
        state,
        ReleaseLogPrefixes.Monorepo,
        "default-remote-check-failure-answer",
        allArgs { case RemoteCheckFailureDefault(value) => value },
        DecisionDefaultsSupport.renderYesNo,
        warnOnDuplicates
      ),
      upstreamBehindAnswer = DecisionDefaultsSupport.resolveLast(
        state,
        ReleaseLogPrefixes.Monorepo,
        "default-upstream-behind-answer",
        allArgs { case UpstreamBehindDefault(value) => value },
        DecisionDefaultsSupport.renderYesNo,
        warnOnDuplicates
      ),
      pushAnswer = DecisionDefaultsSupport.resolveLast(
        state,
        ReleaseLogPrefixes.Monorepo,
        "default-push-answer",
        allArgs { case PushDefault(value) => value },
        DecisionDefaultsSupport.renderYesNo,
        warnOnDuplicates
      )
    )

    ReleaseDecisionDefaults.merge(cliDefaults, ReleaseDecisionDefaults.fromState(state))
  }
}
