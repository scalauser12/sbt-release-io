package io.release.monorepo

import cats.effect.IO
import cats.effect.Resource
import io.release.internal.CheckModeOutput
import io.release.internal.CommandRuntimeSupport
import io.release.internal.ExecutionFlags
import io.release.internal.ReleaseCommandRunner
import io.release.internal.ReleaseDecisionDefaults
import io.release.internal.ReleaseLogPrefixes
import io.release.internal.SbtRuntime
import io.release.monorepo.MonorepoStepAliases.AnyStep
import sbt.{internal as _, *}

/** Internal runtime helpers for monorepo command planning and execution.
  *
  * == Monorepo command path ==
  *
  * {{{
  * sbt "releaseIOMonorepo [projects] [flags]"
  *   → MonorepoReleasePlugin registers the sbt command
  *   → MonorepoCommandExecution.buildCommandInputs   (parse CLI into MonorepoReleasePlan)
  *   → MonorepoCommandExecution.runPreparedCommand    (resolve hooks, compile into steps)
  *   → MonorepoComposer.compose                       (split at selection boundary)
  *       ├─ setup segment  → ExecutionEngine.runSequentialValidateThenExecute
  *       └─ main segment   → ExecutionEngine.runMainSegment
  * }}}
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
      steps: Seq[AnyStep]
  )

  def doHelp(state: State, commandName: String): State =
    ReleaseCommandRunner.runSync(state, ReleaseLogPrefixes.Monorepo) {
      ReleaseCommandRunner
        .logLines(state, ReleaseLogPrefixes.Monorepo, MonorepoPreflight.helpLines(commandName))
        .as(state)
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
    IO.blocking {
      val resolvedHooks     = MonorepoHookConfiguration.resolve(state)
      val resourceHooks     = runtime.resolveResourceHooks(state)
      val materializedHooks = MonorepoResourceHooks.materialize(resourceHooks, maybeResource)
      val mergedHooks       = resolvedHooks.mergeWith(materializedHooks)

      CompiledMonorepoSteps(steps = MonorepoLifecycle.compile(mergedHooks))
    }

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
    val cleanStateFn: State => State =
      state => CommandRuntimeSupport.cleanReleaseState(state, loadedProjectRefs(state))

    runPreparedCommand(
      state = state,
      cleanState = cleanStateFn
    )(
      cleanState =>
        prepareCommand(
          cleanState,
          args,
          runtime,
          interactiveEnabled,
          warnOnDuplicates
        ),
      (command: PlannedCommand) =>
        MonorepoPreparedSession.prepare(command.cleanState, command.plan).flatMap { session =>
          run(command, session)
        }
    )
  }

  private def runPlannedRelease[T](
      command: PlannedCommand,
      session: MonorepoPreparedSession,
      runtime: CommandRuntime[T]
  ): IO[State] =
    for {
      finalCtx <- runtime.resource.use { resourceValue =>
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
                        MonorepoComposer.compose(runProcess.steps, command.flags.crossBuild)(
                          preparedCtx
                        )
                    } yield finalCtx
                  }
      result   <- finalizeReleaseResult(
                    finalCtx,
                    cleanState = state =>
                      CommandRuntimeSupport.cleanReleaseState(
                        state,
                        loadedProjectRefs(state)
                      )
                  )
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
    ReleaseCommandRunner.logLines(state, ReleaseLogPrefixes.Monorepo, lines)

  private def runPreparedCommand[Inputs](
      state: State,
      cleanState: State => State
  )(
      prepare: State => IO[Either[State, Inputs]],
      run: Inputs => IO[State]
  ): State = {
    val cleanedState = cleanState(state)
    val program      = prepare(cleanedState).flatMap {
      case Left(failedState) => IO.pure(failedState)
      case Right(inputs)     => run(inputs)
    }

    ReleaseCommandRunner.runSync(cleanedState, ReleaseLogPrefixes.Monorepo)(program)
  }

  private def finalizeReleaseResult(
      ctx: MonorepoContext,
      cleanState: State => State
  ): IO[State] =
    IO.blocking(ctx.withState(cleanState(ctx.state)))
      .flatMap(cleanedCtx =>
        ReleaseCommandRunner.handleReleaseResult(cleanedCtx, ReleaseLogPrefixes.Monorepo)
      )

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

    val cliDefaults = ReleaseDecisionDefaults.resolveFromCli(
      state,
      ReleaseLogPrefixes.Monorepo,
      warnOnDuplicates
    )(
      tagExistsMatches = allArgs { case TagDefault(value) => value },
      snapshotMatches = allArgs { case SnapshotDependenciesDefault(value) => value },
      remoteMatches = allArgs { case RemoteCheckFailureDefault(value) => value },
      upstreamMatches = allArgs { case UpstreamBehindDefault(value) => value },
      pushMatches = allArgs { case PushDefault(value) => value }
    )

    ReleaseDecisionDefaults.merge(cliDefaults, ReleaseDecisionDefaults.fromState(state))
  }
}
