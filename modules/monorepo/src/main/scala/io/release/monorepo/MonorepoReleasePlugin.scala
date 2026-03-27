package io.release.monorepo

import cats.effect.IO
import cats.effect.Resource
import io.release.PluginLikeSupport
import io.release.ReleaseKeys
import io.release.ReleasePluginIO
import io.release.internal.CheckModeOutput
import io.release.internal.ExecutionFlags
import io.release.internal.ReleaseCommandRunner
import io.release.internal.ReleaseLogPrefixes
import io.release.monorepo.steps.MonorepoReleaseSteps
import sbt.Keys.*
import sbt.complete.DefaultParsers.*
import sbt.complete.Parser
import sbt.{internal as _, *}

import scala.annotation.nowarn

/** Base trait for resource-parameterized monorepo release plugins. Each release step
  * is a function `T => MonorepoStepIO` where `T` is a resource acquired once for the
  * entire release process.
  *
  * A release command (named by [[commandName]]) and default settings are registered automatically.
  *
  * To coexist with the default [[MonorepoReleasePlugin]], use `noTrigger` and override
  * [[commandName]]:
  * {{{
  * object MyMonorepoRelease extends MonorepoReleasePluginLike[HttpClient] {
  *   override def trigger     = noTrigger
  *   override def commandName = "releaseMonorepoCustom"
  *   override def resource    = Resource.make(IO(new HttpClient()))(c => IO(c.close()))
  * }
  * // In build.sbt: enablePlugins(MyMonorepoRelease)
  * // Run with:     sbt releaseMonorepoCustom with-defaults
  * }}}
  *
  * '''Do not add `object autoImport`''' to custom plugins. When both [[MonorepoReleasePlugin]]
  * and a custom plugin define autoImport, the build gets ambiguous references
  * (e.g. `reference to releaseIOMonorepoProcess is ambiguous`). [[MonorepoReleasePlugin]] is
  * on the classpath (same JAR) and sbt imports its autoImport into build.sbt automatically,
  * so you only need `enablePlugins(CustomReleasePlugin)`.
  */
trait MonorepoReleasePluginLike[T]
    extends AutoPlugin
    with MonorepoReleaseIO
    with PluginLikeSupport[MonorepoStepIO, T] {

  override def requires: Plugins = ReleasePluginIO

  protected def stepName(step: MonorepoStepIO): String = step.name

  /** The resource acquired once for the entire monorepo release process and passed to each step. */
  def resource: Resource[IO, T]

  /** Resource-aware hooks compiled into the normal monorepo hook/policy lifecycle.
    *
    * Use this when the built-in lifecycle points are sufficient but the hook logic needs the
    * shared plugin resource. Overriding this method keeps the plugin on compiled hook mode:
    * `check` runs only the resource-free `validate` functions, while `run` acquires [[resource]]
    * and runs both validation and execution.
    */
  protected def monorepoResourceHooks(state: State): MonorepoResourceHooks[T] =
    MonorepoResourceHooks.empty

  /** The monorepo release steps. Reads plain steps from the `releaseIOMonorepoProcess` setting
    * and lifts each into a resource-ignoring function. Override only when you need legacy
    * raw-process customization beyond what hooks and resource-aware hooks can express.
    *
    * Hooks and policies are the preferred customization path. Changing the effective release
    * process returned from this method switches the real release run into legacy raw-process
    * mode, where the hook/policy settings are ignored and the custom process wiring remains
    * authoritative. `check` stays on the plain configured process unless
    * [[monorepoReleaseCheckProcess]] is also customized. Merely defining a custom plugin or
    * overriding unrelated members such as [[commandName]] or [[resource]] does not.
    */
  @deprecated(
    "Prefer `releaseIOMonorepoEnable*` policies and `releaseIOMonorepo*Hooks`; changing the effective process returned from `monorepoReleaseProcess` switches the real release run into legacy raw-process mode.",
    "0.7.0"
  )
  protected def monorepoReleaseProcess(state: State): Seq[T => MonorepoStepIO] =
    liftSteps(Project.extract(state).get(releaseIOMonorepoProcess))

  /** Resource-free steps used by `check`.
    *
    * Defaults to the plain configured `releaseIOMonorepoProcess` so `check` avoids acquiring the
    * plugin resource. Prefer [[monorepoResourceHooks]] when the built-in lifecycle points are
    * sufficient. Override this only to add legacy raw-process preflight equivalents for custom
    * step wiring.
    *
    * Hooks and policies are the preferred customization path. Changing the effective preflight
    * process returned from this method switches `check` into legacy raw-process mode, where the
    * hook/policy settings are ignored and the custom process wiring remains authoritative.
    * Merely defining a custom plugin or overriding unrelated members such as [[commandName]] or
    * [[resource]] does not.
    */
  @deprecated(
    "Prefer `releaseIOMonorepoEnable*` policies and `releaseIOMonorepo*Hooks`; changing the effective process returned from `monorepoReleaseCheckProcess` switches `check` into legacy raw-process mode.",
    "0.7.0"
  )
  protected def monorepoReleaseCheckProcess(state: State): Seq[MonorepoStepIO] =
    Project.extract(state).get(releaseIOMonorepoProcess)

  /** The name of the monorepo release command. Override to use a different name
    * when coexisting with [[MonorepoReleasePlugin]].
    */
  protected def commandName: String = "releaseIOMonorepo"

  override lazy val projectSettings: Seq[Setting[?]] =
    MonorepoReleaseIO.monorepoDefaultSettings ++ Seq(
      commands += Command(commandName)(monorepoParser)(handleMonorepoRelease)
    )

  // ── Parser ──────────────────────────────────────────────────────────

  /** Structured parser for monorepo release commands.
    *
    * Uses live project ids for explicit project-name completion and emits canonical tokens for
    * the shared monorepo CLI decoder.
    */
  protected def monorepoParser(state: State): Parser[Seq[String]] =
    MonorepoCommandParsers.buildFromState(state, commandName)

  // ── Parsed flags ───────────────────────────────────────────────────

  protected case class ReleaseFlags(
      useDefaults: Boolean,
      skipTests: Boolean,
      crossBuild: Boolean,
      allChanged: Boolean,
      skipPublish: Boolean,
      interactive: Boolean
  )

  private final class PlannedCommand(
      val cleanState: State,
      val flags: ReleaseFlags,
      val plan: MonorepoReleasePlan
  )

  private def parseFlags(args: Seq[MonorepoCli.Arg], extracted: Extracted): ReleaseFlags = {
    import MonorepoCli.Arg.*
    ReleaseFlags(
      useDefaults = args.contains(WithDefaults),
      skipTests = args.contains(SkipTests) || extracted.get(releaseIOMonorepoSkipTests),
      crossBuild = args.contains(CrossBuild) || extracted.get(releaseIOMonorepoCrossBuild),
      allChanged = args.contains(AllChanged),
      skipPublish = extracted.get(releaseIOMonorepoSkipPublish),
      interactive = extracted.get(releaseIOMonorepoInteractive)
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
      releaseVersionPairs = args.collect { case ReleaseVersion(p, v) => p -> v },
      nextVersionPairs = args.collect { case NextVersion(p, v) => p -> v },
      commandName = commandName
    )
  }

  private def logLines(state: State, lines: Seq[String]): IO[Unit] =
    ReleaseCommandRunner.logLines(state, ReleaseLogPrefixes.Monorepo, lines)

  private val ReleaseProcessLegacyReason =
    "`monorepoReleaseProcess` differs from the configured raw process"

  private final class ResolvedProcessMode(
      val releaseSteps: Seq[T => MonorepoStepIO],
      val checkSteps: Seq[MonorepoStepIO],
      val legacyMode: Boolean,
      val legacyReasons: Seq[String]
  )

  private object ResolvedProcessMode {
    def apply(
        releaseSteps: Seq[T => MonorepoStepIO],
        checkSteps: Seq[MonorepoStepIO],
        legacyMode: Boolean,
        legacyReasons: Seq[String]
    ): ResolvedProcessMode =
      new ResolvedProcessMode(releaseSteps, checkSteps, legacyMode, legacyReasons)
  }

  private final class ResolvedReleaseRun(
      val steps: Seq[MonorepoStepIO],
      val legacyMode: Boolean,
      val legacyReasons: Seq[String]
  )

  private object ResolvedReleaseRun {
    def apply(
        steps: Seq[MonorepoStepIO],
        legacyMode: Boolean,
        legacyReasons: Seq[String]
    ): ResolvedReleaseRun =
      new ResolvedReleaseRun(steps, legacyMode, legacyReasons)
  }

  @nowarn("cat=deprecation")
  private def resolveProcessMode(state: State): IO[ResolvedProcessMode] =
    IO.blocking {
      val extracted             = Project.extract(state)
      val configuredRaw         = extracted.get(MonorepoReleaseIO._releaseIOMonorepoProcess)
      val configuredCheck       = monorepoReleaseCheckProcess(state)
      val configuredRelease     = monorepoReleaseProcess(state)
      val rawProcessChanged     = configuredRaw != MonorepoReleaseSteps.defaults
      val checkProcessChanged   = configuredCheck != configuredRaw
      val releaseProcessChanged = configuredRelease.length != configuredRaw.length
      val legacyReasons         = Seq(
        if (rawProcessChanged) Some("`releaseIOMonorepoProcess` differs from defaults") else None,
        if (checkProcessChanged)
          Some("`monorepoReleaseCheckProcess` differs from the configured raw process")
        else None,
        if (releaseProcessChanged)
          Some("`monorepoReleaseProcess` differs from the configured raw process")
        else None
      ).flatten
      val legacyMode            = legacyReasons.nonEmpty

      if (legacyMode)
        ResolvedProcessMode(
          releaseSteps = configuredRelease,
          checkSteps = configuredCheck,
          legacyMode = true,
          legacyReasons = legacyReasons
        )
      else {
        val compiled = MonorepoHookCompiler.compile(
          mergeHookConfiguration(
            MonorepoHookCompiler.resolve(state),
            monorepoResourceHooks(state),
            maybeResource = None
          )
        )

        ResolvedProcessMode(
          releaseSteps = liftSteps(compiled),
          checkSteps = compiled,
          legacyMode = false,
          legacyReasons = legacyReasons
        )
      }
    }

  @nowarn("cat=deprecation")
  private def resolveReleaseRun(
      state: State,
      processMode: ResolvedProcessMode,
      resourceValue: T
  ): IO[ResolvedReleaseRun] =
    if (processMode.legacyMode)
      IO.pure(
        ResolvedReleaseRun(
          steps = processMode.releaseSteps.map(_(resourceValue)),
          legacyMode = true,
          legacyReasons = processMode.legacyReasons
        )
      )
    else
      IO.blocking {
        val extracted         = Project.extract(state)
        val configuredRaw     = extracted.get(MonorepoReleaseIO._releaseIOMonorepoProcess)
        val configuredRelease = monorepoReleaseProcess(state).map(_(resourceValue))
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
              mergeHookConfiguration(
                MonorepoHookCompiler.resolve(state),
                monorepoResourceHooks(state),
                maybeResource = Some(resourceValue)
              )
            ),
            legacyMode = false,
            legacyReasons = Seq.empty
          )
      }

  private def mergeHookConfiguration(
      plainHooks: MonorepoHookConfiguration,
      resourceHooks: MonorepoResourceHooks[T],
      maybeResource: Option[T]
  ): MonorepoHookConfiguration =
    MonorepoHookConfiguration(
      enableSnapshotDependenciesCheck = plainHooks.enableSnapshotDependenciesCheck,
      enableRunClean = plainHooks.enableRunClean,
      enableRunTests = plainHooks.enableRunTests,
      enableTagging = plainHooks.enableTagging,
      enablePublish = plainHooks.enablePublish,
      enablePush = plainHooks.enablePush,
      beforeSelectionHooks = plainHooks.beforeSelectionHooks ++ materializeGlobalHooks(
        resourceHooks.beforeSelectionHooks,
        maybeResource
      ),
      afterSelectionHooks = plainHooks.afterSelectionHooks ++ materializeGlobalHooks(
        resourceHooks.afterSelectionHooks,
        maybeResource
      ),
      beforeVersionResolutionHooks =
        plainHooks.beforeVersionResolutionHooks ++ materializeProjectHooks(
          resourceHooks.beforeVersionResolutionHooks,
          maybeResource
        ),
      afterVersionResolutionHooks =
        plainHooks.afterVersionResolutionHooks ++ materializeProjectHooks(
          resourceHooks.afterVersionResolutionHooks,
          maybeResource
        ),
      beforeReleaseVersionWriteHooks =
        plainHooks.beforeReleaseVersionWriteHooks ++ materializeProjectHooks(
          resourceHooks.beforeReleaseVersionWriteHooks,
          maybeResource
        ),
      afterReleaseVersionWriteHooks =
        plainHooks.afterReleaseVersionWriteHooks ++ materializeProjectHooks(
          resourceHooks.afterReleaseVersionWriteHooks,
          maybeResource
        ),
      beforeReleaseCommitHooks = plainHooks.beforeReleaseCommitHooks ++ materializeGlobalHooks(
        resourceHooks.beforeReleaseCommitHooks,
        maybeResource
      ),
      afterReleaseCommitHooks = plainHooks.afterReleaseCommitHooks ++ materializeGlobalHooks(
        resourceHooks.afterReleaseCommitHooks,
        maybeResource
      ),
      beforeTagHooks = plainHooks.beforeTagHooks ++ materializeProjectHooks(
        resourceHooks.beforeTagHooks,
        maybeResource
      ),
      afterTagHooks = plainHooks.afterTagHooks ++ materializeProjectHooks(
        resourceHooks.afterTagHooks,
        maybeResource
      ),
      beforePublishHooks = plainHooks.beforePublishHooks ++ materializeProjectHooks(
        resourceHooks.beforePublishHooks,
        maybeResource
      ),
      afterPublishHooks = plainHooks.afterPublishHooks ++ materializeProjectHooks(
        resourceHooks.afterPublishHooks,
        maybeResource
      ),
      beforeNextVersionWriteHooks =
        plainHooks.beforeNextVersionWriteHooks ++ materializeProjectHooks(
          resourceHooks.beforeNextVersionWriteHooks,
          maybeResource
        ),
      afterNextVersionWriteHooks = plainHooks.afterNextVersionWriteHooks ++ materializeProjectHooks(
        resourceHooks.afterNextVersionWriteHooks,
        maybeResource
      ),
      beforeNextCommitHooks = plainHooks.beforeNextCommitHooks ++ materializeGlobalHooks(
        resourceHooks.beforeNextCommitHooks,
        maybeResource
      ),
      afterNextCommitHooks = plainHooks.afterNextCommitHooks ++ materializeGlobalHooks(
        resourceHooks.afterNextCommitHooks,
        maybeResource
      ),
      beforePushHooks = plainHooks.beforePushHooks ++ materializeGlobalHooks(
        resourceHooks.beforePushHooks,
        maybeResource
      ),
      afterPushHooks = plainHooks.afterPushHooks ++ materializeGlobalHooks(
        resourceHooks.afterPushHooks,
        maybeResource
      )
    )

  private def materializeGlobalHooks(
      hooks: Seq[MonorepoGlobalResourceHookIO[T]],
      maybeResource: Option[T]
  ): Seq[MonorepoGlobalHookIO] =
    hooks.map { hook =>
      MonorepoGlobalHookIO(
        name = hook.name,
        execute = ctx =>
          maybeResource.fold(IO.pure(ctx))(resourceValue => hook.execute(resourceValue)(ctx)),
        validate = hook.validate
      )
    }

  private def materializeProjectHooks(
      hooks: Seq[MonorepoProjectResourceHookIO[T]],
      maybeResource: Option[T]
  ): Seq[MonorepoProjectHookIO] =
    hooks.map { hook =>
      MonorepoProjectHookIO(
        name = hook.name,
        execute = (ctx, project) =>
          maybeResource.fold(IO.pure(ctx))(resourceValue =>
            hook.execute(resourceValue)(ctx, project)
          ),
        validate = hook.validate
      )
    }

  private def logLegacyModeWarning(
      state: State,
      legacyMode: Boolean,
      legacyReasons: Seq[String]
  ): IO[Unit] =
    if (!legacyMode) IO.unit
    else
      IO.blocking {
        val reasons = legacyReasons.mkString("; ")
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

  private def prepareCommand(
      cleanState: State,
      args: Seq[MonorepoCli.Arg]
  ): IO[Either[State, PlannedCommand]] = {
    val extracted = Project.extract(cleanState)
    val flags     = parseFlags(args, extracted)

    MonorepoReleasePlan
      .build(cleanState, plannerInputs(args, flags, commandName))
      .map(_.map(new PlannedCommand(cleanState, flags, _)))
  }

  private def handleMonorepoRelease(state: State, tokens: Seq[String]): State =
    MonorepoCli.parse(tokens, commandName) match {
      case Left(message) =>
        state.log.error(s"${ReleaseLogPrefixes.Monorepo} $message")
        state.fail
      case Right(parsed) =>
        parsed.mode match {
          case MonorepoCli.CommandMode.Help  => doMonorepoHelp(state)
          case MonorepoCli.CommandMode.Check => doMonorepoCheck(state, parsed.args)
          case MonorepoCli.CommandMode.Run   => doMonorepoRelease(state, parsed.args)
        }
    }

  protected def doMonorepoHelp(state: State): State = {
    val program = logLines(state, MonorepoPreflight.helpLines(commandName))
    ReleaseCommandRunner.runSync(state, ReleaseLogPrefixes.Monorepo)(program.as(state))
  }

  // ── Shared preparation ────────────────────────────────────────────

  private def prepareSession(command: PlannedCommand): IO[MonorepoPreparedSession] =
    MonorepoPreparedSession.prepare(command.cleanState, command.plan)

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

  // ── Release execution ───────────────────────────────────────────────

  protected def doMonorepoRelease(state: State, args: Seq[MonorepoCli.Arg]): State = {
    val cleanState         = state.remove(ReleaseKeys.versions)
    val program: IO[State] = prepareCommand(cleanState, args).flatMap {
      case Left(failedState) => IO.pure(failedState)
      case Right(command)    => runPlannedRelease(command)
    }
    ReleaseCommandRunner.runSync(cleanState, ReleaseLogPrefixes.Monorepo)(program)
  }

  private def runPlannedRelease(command: PlannedCommand): IO[State] = {
    for {
      session  <- prepareSession(command)
      process  <- resolveProcessMode(session.cleanState)
      finalCtx <- resource.use { t =>
                    for {
                      runProcess <- resolveReleaseRun(session.cleanState, process, t)
                      _          <- logLegacyModeWarning(
                                      session.cleanState,
                                      runProcess.legacyMode,
                                      runProcess.legacyReasons
                                    )
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
  }

  protected def doMonorepoCheck(state: State, args: Seq[MonorepoCli.Arg]): State = {
    val cleanState         = state.remove(ReleaseKeys.versions)
    val program: IO[State] = prepareCommand(cleanState, args).flatMap {
      case Left(failedState) => IO.pure(failedState)
      case Right(command)    => runPlannedCheck(command)
    }
    ReleaseCommandRunner.runSync(cleanState, ReleaseLogPrefixes.Monorepo)(program)
  }

  private def runPlannedCheck(command: PlannedCommand): IO[State] = {
    for {
      session <- prepareSession(command)
      process <- resolveProcessMode(session.cleanState)
      _       <- logLegacyModeWarning(
                   session.cleanState,
                   process.legacyMode,
                   process.legacyReasons
                 )
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
  }
}

/** Default monorepo release plugin using `Unit` as the resource type (no external resource needed).
  *
  * Must be explicitly enabled on the root project:
  * {{{
  * // build.sbt
  * lazy val root = (project in file("."))
  *   .aggregate(core, api)
  *   .enablePlugins(MonorepoReleasePlugin)
  * }}}
  *
  * Then run: `sbt releaseIOMonorepo core with-defaults`
  */
object MonorepoReleasePlugin extends MonorepoReleasePluginLike[Unit] {

  override def trigger = noTrigger

  override def resource: Resource[IO, Unit] = Resource.unit

  object autoImport extends MonorepoReleaseIO
}
