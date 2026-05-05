package io.release.monorepo.internal

import cats.effect.IO
import cats.effect.Resource
import io.release.monorepo.*
import io.release.monorepo.internal.MonorepoStepAliases.AnyStep
import io.release.runtime.ExecutionFlags
import io.release.runtime.ReleaseDecisionDefaults
import io.release.runtime.ReleaseLogPrefixes
import io.release.runtime.command.CheckModeOutput
import io.release.runtime.command.CommandStateSupport
import io.release.runtime.command.ReleaseCommandRunner
import io.release.runtime.workflow.DecisionDefaultsSupport
import sbt.{internal as _, *}

/** Internal runtime helpers for monorepo command planning and execution.
  *
  * == Monorepo command path ==
  *
  * {{{
  * sbt "releaseIOMonorepo [projects] [flags]"
  *   → MonorepoReleasePlugin registers the sbt command
  *   → MonorepoCommandExecution.prepareCommand        (resolve flags/defaults and build the plan)
  *   → ReleaseCommandRunner.runPreparedCommand        (clean state and run the prepared session)
  *   → MonorepoComposer.compose                       (split at selection boundary)
  *       ├─ setup segment  → ExecutionEngine.runSequentialValidateThenExecute
  *       │                    (through `detect-or-select-projects` and any immediate
  *       │                     `after-selection:*` hooks)
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

  private val cliExtractors = DecisionDefaultsSupport.CliExtractors[MonorepoCli.Arg](
    tagExists = { case MonorepoCli.Arg.TagDefault(value) => value },
    snapshotDependencies = { case MonorepoCli.Arg.SnapshotDependenciesDefault(value) => value },
    remoteCheckFailure = { case MonorepoCli.Arg.RemoteCheckFailureDefault(value) => value },
    upstreamBehind = { case MonorepoCli.Arg.UpstreamBehindDefault(value) => value },
    push = { case MonorepoCli.Arg.PushDefault(value) => value }
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
      warnOnDuplicates = true
    ) { (command, session) =>
      runPlannedCheck(command, session, runtime)
    }

  def resolveProcessMode[T](
      state: State,
      runtime: CommandRuntime[T]
  ): IO[Seq[AnyStep]] =
    compileMergedSteps(state, runtime, maybeResource = None)

  def resolveReleaseRun[T](
      state: State,
      resourceValue: T,
      runtime: CommandRuntime[T]
  ): IO[Seq[AnyStep]] =
    compileMergedSteps(state, runtime, maybeResource = Some(resourceValue))

  private def compileMergedSteps[T](
      state: State,
      runtime: CommandRuntime[T],
      maybeResource: Option[T]
  ): IO[Seq[AnyStep]] =
    for {
      mergedHooks <- IO.blocking {
                       val resolvedHooks     = MonorepoHookConfiguration.resolve(state)
                       val resourceHooks     = runtime.resolveResourceHooks(state)
                       val materializedHooks =
                         MonorepoResourceHooks.materialize(resourceHooks, maybeResource)
                       resolvedHooks.mergeWith(materializedHooks)
                     }
      steps       <- MonorepoLifecycle.compile(mergedHooks)
    } yield steps

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
      flags    <- IO.blocking(resolveFlags(cleanState, args, runtime, interactiveEnabled))
      defaults <- IO.blocking(
                    DecisionDefaultsSupport.resolveFromArgs(
                      cleanState,
                      ReleaseLogPrefixes.Monorepo,
                      args,
                      cliExtractors,
                      warnOnDuplicates
                    )
                  )
      planned  <- MonorepoReleasePlan.build(
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
      state => CommandStateSupport.cleanReleaseState(state)

    ReleaseCommandRunner.runPreparedCommand(
      state = state,
      cleanState = cleanStateFn,
      logPrefix = ReleaseLogPrefixes.Monorepo
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
        IO.blocking(runtime.resolveInteractiveEnabled(command.cleanState)).flatMap {
          configuredInteractive =>
            MonorepoPreparedSession
              .prepare(command.cleanState, command.plan, configuredInteractive)
              .flatMap(session => run(command, session))
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
                      steps    <- resolveReleaseRun(session.cleanState, resourceValue, runtime)
                      _        <- IO.blocking(
                                    logReleaseStart(
                                      session.cleanState,
                                      steps.length,
                                      session.context.projects.length,
                                      command.flags
                                    )
                                  )
                      finalCtx <-
                        MonorepoComposer.compose(steps, command.flags.crossBuild)(
                          session.context
                        )
                    } yield finalCtx
                  }
      result   <- ReleaseCommandRunner.finalizeWithCleanState(
                    finalCtx,
                    cleanState = state => CommandStateSupport.cleanReleaseState(state),
                    prefix = ReleaseLogPrefixes.Monorepo
                  )
    } yield result

  private def runPlannedCheck[T](
      command: PlannedCommand,
      session: MonorepoPreparedSession,
      runtime: CommandRuntime[T]
  ): IO[State] =
    for {
      steps   <- resolveProcessMode(session.cleanState, runtime)
      _       <- CheckModeOutput.logCheckStart(
                   session.cleanState,
                   ReleaseLogPrefixes.Monorepo,
                   steps.length
                 )
      summary <- MonorepoPreflight.check(session, steps)
      _       <- logLines(session.cleanState, MonorepoPreflight.renderSummary(summary))
      _       <- CheckModeOutput.logCheckPassed(
                   session.cleanState,
                   ReleaseLogPrefixes.Monorepo
                 )
    } yield command.cleanState

  private def logLines(state: State, lines: Seq[String]): IO[Unit] =
    ReleaseCommandRunner.logLines(state, ReleaseLogPrefixes.Monorepo, lines)

  private[monorepo] def releaseStartLines(
      stepCount: Int,
      projectCount: Int,
      flags: ReleaseFlags
  ): List[String] = {
    val prefix = ReleaseLogPrefixes.Monorepo

    List(
      s"$prefix Starting monorepo release...",
      s"$prefix $stepCount steps, $projectCount configured project(s)"
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
}
