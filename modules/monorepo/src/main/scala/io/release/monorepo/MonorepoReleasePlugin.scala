package io.release.monorepo

import cats.effect.IO
import cats.effect.Resource
import cats.syntax.all.*
import io.release.PluginLikeSupport
import io.release.ReleaseKeys
import io.release.ReleasePluginIO
import io.release.internal.CheckModeOutput
import io.release.internal.ExecutionFlags
import io.release.internal.ReleaseCommandRunner
import io.release.internal.ReleaseLogPrefixes
import io.release.monorepo.MonorepoTagStrategy as MonorepoTagStrategy_
import io.release.steps.StepHelpers
import sbt.Keys.*
import sbt.complete.DefaultParsers.*
import sbt.complete.Parser
import sbt.{internal as _, *}

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

  /** The monorepo release steps. Reads plain steps from the `releaseIOMonorepoProcess` setting
    * and lifts each into a resource-ignoring function. Override to append resource-aware steps.
    */
  protected def monorepoReleaseProcess(state: State): Seq[T => MonorepoStepIO] =
    liftSteps(Project.extract(state).get(releaseIOMonorepoProcess))

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
      interactive: Boolean,
      tagStrategy: MonorepoTagStrategy
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
      interactive = extracted.get(releaseIOMonorepoInteractive),
      tagStrategy = extracted.get(releaseIOMonorepoTagStrategy)
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
      globalReleaseVersions = args.collect { case GlobalReleaseVersion(v) => v },
      globalNextVersions = args.collect { case GlobalNextVersion(v) => v },
      commandName = commandName
    )
  }

  private def logLines(state: State, lines: Seq[String]): IO[Unit] =
    lines.toList.traverse_(line =>
      IO.blocking(state.log.info(s"${ReleaseLogPrefixes.Monorepo} $line"))
    )

  private def prepareCommand(
      state: State,
      args: Seq[MonorepoCli.Arg]
  ): IO[Either[State, PlannedCommand]] = {
    val extracted  = Project.extract(state)
    val flags      = parseFlags(args, extracted)
    val cleanState = state.remove(ReleaseKeys.versions)

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

  // ── Context building ──────────────────────────────────────────────

  private def buildContext(
      state: State,
      flags: ReleaseFlags,
      plan: MonorepoReleasePlan
  ): IO[MonorepoContext] =
    MonorepoProjectResolver.resolveAll(state).map { projects =>
      MonorepoContext(
        state = state,
        projects = projects,
        skipTests = plan.flags.skipTests,
        skipPublish = plan.flags.skipPublish,
        interactive = plan.flags.interactive,
        tagStrategy = flags.tagStrategy
      ).withReleasePlan(plan)
    }

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
    val program: IO[State] = for {
      plannedEither <- prepareCommand(state, args)
      result        <- plannedEither match {
                         case Left(failedState) => IO.pure(failedState)
                         case Right(command)    =>
                           val plannedState = command.cleanState
                           for {
                             stepFns    <- IO.blocking(monorepoReleaseProcess(plannedState))
                             initialCtx <- buildContext(plannedState, command.flags, command.plan)
                             _          <- IO.blocking(
                                             logReleaseStart(
                                               plannedState,
                                               stepFns.length,
                                               initialCtx.projects.length,
                                               command.flags
                                             )
                                           )
                             finalCtx   <- resource.use { t =>
                                             val steps = stepFns.map(_(t))
                                             MonorepoStepIO.compose(
                                               steps,
                                               command.flags.crossBuild
                                             )(
                                               initialCtx
                                             )
                                           }
                             result     <- if (finalCtx.failed) {
                                             val cause = finalCtx.failureCause
                                               .map(e => StepHelpers.errorMessage(e))
                                               .getOrElse("unknown error")
                                             IO.blocking(
                                               finalCtx.state.log.error(
                                                 s"${ReleaseLogPrefixes.Monorepo} Release failed: $cause"
                                               )
                                             ).as(finalCtx.state.fail)
                                           } else {
                                             IO.blocking(
                                               finalCtx.state.log.info(
                                                 s"${ReleaseLogPrefixes.Monorepo} Monorepo release completed successfully!"
                                               )
                                             ).as(finalCtx.state)
                                           }
                           } yield result
                       }
    } yield result

    // unsafeRunSync() blocks the sbt command thread — unavoidable at the sbt plugin boundary.
    ReleaseCommandRunner.runSync(state, ReleaseLogPrefixes.Monorepo)(program)
  }

  protected def doMonorepoCheck(state: State, args: Seq[MonorepoCli.Arg]): State = {
    val program: IO[State] = for {
      plannedEither <- prepareCommand(state, args)
      result        <- plannedEither match {
                         case Left(failedState) => IO.pure(failedState)
                         case Right(command)    =>
                           val plannedState = command.cleanState
                           for {
                             stepFns    <- IO.blocking(monorepoReleaseProcess(plannedState))
                             initialCtx <- buildContext(plannedState, command.flags, command.plan)
                             _          <- IO.blocking {
                                             plannedState.log.info(
                                               s"${ReleaseLogPrefixes.Monorepo} Starting preflight checks..."
                                             )
                                             plannedState.log.info(
                                               s"${ReleaseLogPrefixes.Monorepo} ${stepFns.length} steps configured"
                                             )
                                             plannedState.log.info(
                                               s"${ReleaseLogPrefixes.Monorepo} ${CheckModeOutput.CheckModeLogSummary}"
                                             )
                                           }
                             summary    <- resource.use { t =>
                                             val steps = stepFns.map(_(t))
                                             MonorepoPreflight.check(
                                               initialCtx,
                                               steps,
                                               command.flags.crossBuild
                                             )
                                           }
                             _          <- logLines(plannedState, MonorepoPreflight.renderSummary(summary))
                             _          <- IO.blocking(
                                             plannedState.log.info(
                                               s"${ReleaseLogPrefixes.Monorepo} Preflight checks passed."
                                             )
                                           )
                           } yield command.cleanState
                       }
    } yield result

    ReleaseCommandRunner.runSync(state, ReleaseLogPrefixes.Monorepo)(program)
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

  object autoImport extends MonorepoReleaseIO {
    val MonorepoTagStrategy = MonorepoTagStrategy_
  }
}
