package io.release

import cats.effect.{IO, Resource}
import cats.effect.unsafe.implicits.global
import io.release.steps.ReleaseSteps
import sbt.*
import sbt.Keys.*
import sbt.complete.DefaultParsers.*
import sbt.complete.Parser

/** Base trait for resource-parameterized release plugins. Each release step is a function
  * `T => ReleaseStepIO` where `T` is a resource acquired once for the entire release process.
  *
  * Plain `ReleaseStepIO` values are implicitly lifted to `T => ReleaseStepIO` via [[liftStep]].
  * The `releaseIO` command and default settings are registered automatically.
  *
  * Users extend this trait to create custom plugins with resource lifecycle management:
  * {{{
  * object MyReleasePlugin extends ReleasePluginIOLike[HttpClient] {
  *   override def trigger  = allRequirements
  *   override def resource = Resource.make(IO(new HttpClient()))(c => IO(c.close()))
  *   object autoImport extends ReleaseIO
  * }
  * }}}
  */
trait ReleasePluginIOLike[T] extends AutoPlugin with ReleaseIO {
  import scala.language.implicitConversions

  override def requires: Plugins = plugins.JvmPlugin && sbtrelease.ReleasePlugin

  /** The resource acquired once for the entire release process and passed to each step. */
  def resource: Resource[IO, T]

  /** The release steps. Each step is a function from the resource `T` to a `ReleaseStepIO`.
    * Plain `ReleaseStepIO` values are implicitly lifted via [[liftStep]].
    * Defaults to reading from the `releaseIOProcess` setting.
    */
  protected def releaseProcess(state: State): Seq[T => ReleaseStepIO] =
    Project.extract(state).get(releaseIOProcess).map(liftStep)

  /** Whether cross-building is enabled (before command-line args are applied).
    * Defaults to reading from the `releaseIOCrossBuild` setting.
    */
  protected def crossBuildEnabled(state: State): Boolean =
    Project.extract(state).get(releaseIOCrossBuild)

  /** Whether to skip publish. Defaults to reading from the `releaseIOSkipPublish` setting. */
  protected def skipPublishEnabled(state: State): Boolean =
    Project.extract(state).get(releaseIOSkipPublish)

  /** Whether interactive prompts are enabled.
    * Defaults to reading from the `releaseIOInteractive` setting.
    */
  protected def interactiveEnabled(state: State): Boolean =
    Project.extract(state).get(releaseIOInteractive)

  /** Implicitly lift a plain `ReleaseStepIO` to a resource-ignoring step.
    * This allows mixing plain steps and resource-parameterized steps in `releaseProcess`.
    */
  protected implicit def liftStep(step: ReleaseStepIO): T => ReleaseStepIO = _ => step

  /** Base settings that include command registration. Custom plugins that override
    * `projectSettings` should include `baseReleaseSettings` in their sequence.
    */
  protected def baseReleaseSettings: Seq[Setting[?]] = Seq(releaseIOCommand)

  /** Default values for the release-io setting keys. */
  protected def defaultSettingsValues: Seq[Setting[?]] = Seq(
    releaseIOProcess     := ReleaseSteps.defaults,
    releaseIOCrossBuild  := false,
    releaseIOSkipPublish := false,
    releaseIOInteractive := false
  )

  /** Standalone sbt commands for invoking individual release steps outside the `releaseIO` flow. */
  protected lazy val releaseExtraCommands: Seq[Command] = Seq(
    releaseStepCommand("release-vcs-checks", checkOnly(ReleaseSteps.checkCleanWorkingDir)),
    releaseStepCommand(
      "release-check-snapshot-dependencies",
      checkOnly(ReleaseSteps.checkSnapshotDependencies)
    ),
    releaseStepCommand("release-inquire-versions", actionOnly(ReleaseSteps.inquireVersions)),
    releaseStepCommand("release-set-release-version", actionOnly(ReleaseSteps.setReleaseVersion)),
    releaseStepCommand("release-set-next-version", actionOnly(ReleaseSteps.setNextVersion)),
    releaseStepCommand(
      "release-commit-release-version",
      actionOnly(ReleaseSteps.commitReleaseVersion)
    ),
    releaseStepCommand("release-commit-next-version", actionOnly(ReleaseSteps.commitNextVersion)),
    releaseStepCommand("release-tag-release", actionOnly(ReleaseSteps.tagRelease)),
    releaseStepCommand("release-push-changes", actionOnly(ReleaseSteps.pushChanges))
  )

  override lazy val projectSettings: Seq[Setting[?]] =
    baseReleaseSettings ++ defaultSettingsValues ++ Seq(commands ++= releaseExtraCommands)

  /** Parse results for command-line arguments. */
  protected sealed trait ReleaseArg
  protected object ReleaseArg {
    case object WithDefaults                 extends ReleaseArg
    case object SkipTests                    extends ReleaseArg
    case object CrossBuild                   extends ReleaseArg
    case class ReleaseVersion(value: String) extends ReleaseArg
    case class NextVersion(value: String)    extends ReleaseArg
    case class TagDefault(value: String)     extends ReleaseArg
  }

  /** Parser for releaseIO command arguments. */
  protected lazy val releaseParser: Parser[Seq[ReleaseArg]] = {
    import ReleaseArg.*

    val withDefaults: Parser[ReleaseArg]   = token("with-defaults").map(_ => WithDefaults)
    val skipTests: Parser[ReleaseArg]      = token("skip-tests").map(_ => SkipTests)
    val crossBuild: Parser[ReleaseArg]     = token("cross").map(_ => CrossBuild)
    val releaseVersion: Parser[ReleaseArg] =
      (token("release-version") ~> Space ~> token(NotSpace, "<release version>"))
        .map(ReleaseVersion)
    val nextVersion: Parser[ReleaseArg]    =
      (token("next-version") ~> Space ~> token(NotSpace, "<next version>")).map(NextVersion)
    val tagDefault: Parser[ReleaseArg]     =
      (token("default-tag-exists-answer") ~> Space ~> token(NotSpace, "o|k|a|<tag-name>"))
        .map(TagDefault)

    val arg = withDefaults | skipTests | crossBuild | releaseVersion | nextVersion | tagDefault
    (Space ~> arg).*
  }

  /** Setting that registers the `releaseIO` command. Include in `projectSettings`. */
  protected def releaseIOCommand: Setting[?] =
    commands += Command("releaseIO")(_ => releaseParser)(doReleaseIO)

  /** Strip the check phase from a step, keeping only the action. */
  protected def actionOnly(step: ReleaseStepIO): ReleaseStepIO =
    step.copy(check = ctx => IO.pure(ctx))

  /** Strip the action phase from a step, keeping only the check. */
  protected def checkOnly(step: ReleaseStepIO): ReleaseStepIO =
    step.copy(action = ctx => IO.pure(ctx))

  /** Wrap a release step as a standalone sbt Command. */
  protected def releaseStepCommand(name: String, step: ReleaseStepIO): Command =
    Command.command(name) { state =>
      runExtraReleaseSteps(state, Seq(step))
    }

  /** Run release steps outside the main `releaseIO` flow (e.g. as standalone commands).
    * Hydrates the context from existing state attributes set by prior steps.
    */
  protected def runExtraReleaseSteps(state: State, steps: Seq[ReleaseStepIO]): State = {
    val crossEnabled =
      crossBuildEnabled(state) || state.get(ReleaseKeys.cross).getOrElse(false)
    val skipTests    = state.get(ReleaseKeys.skipTests).getOrElse(false)
    val skipPublish  = skipPublishEnabled(state)
    val interactive  = interactiveEnabled(state)
    val ctx          = initialContext(state, skipTests, skipPublish, interactive)
    // unsafeRunSync() blocks the sbt command thread — unavoidable at the sbt plugin boundary.
    val finalCtx     = ReleaseStepIO.compose(steps, crossEnabled)(ctx).unsafeRunSync()
    finalCtx.state
  }

  /** Build the initial release context from the current state.
    *
    * Besides command-line flags, this hydrates versions/VCS from state/settings so partial
    * command flows (e.g. extra release commands) can continue from prior steps.
    */
  protected def initialContext(
      state: State,
      skipTests: Boolean,
      skipPublish: Boolean,
      interactive: Boolean
  ): ReleaseContext = {
    val maybeVersions = state.get(ReleaseKeys.versions)
    val maybeVcs      = scala.util
      .Try {
        Project.extract(state).get(sbtrelease.ReleasePlugin.autoImport.releaseVcs)
      }
      .toOption
      .flatten

    ReleaseContext(
      state = state,
      versions = maybeVersions,
      vcs = maybeVcs,
      skipTests = skipTests,
      skipPublish = skipPublish,
      interactive = interactive
    )
  }

  private def doReleaseIO(state: State, args: Seq[ReleaseArg]): State = {
    import ReleaseArg.*

    val stepFns = releaseProcess(state)

    // Parse command-line arguments
    val useDefaults   = args.contains(WithDefaults)
    val skipTests     = args.contains(SkipTests)
    val crossFromArgs = args.contains(CrossBuild)
    val crossEnabled  = crossBuildEnabled(state) || crossFromArgs
    val skipPublish   = skipPublishEnabled(state)
    val interactive   = interactiveEnabled(state)

    val releaseVersionArg = args.collectFirst { case ReleaseVersion(v) => v }
    val nextVersionArg    = args.collectFirst { case NextVersion(v) => v }
    val tagDefaultArg     = args.collectFirst { case TagDefault(v) => v }

    // Store parsed arguments in State attributes
    val decoratedState = state
      .put(ReleaseKeys.useDefaults, useDefaults)
      .put(ReleaseKeys.skipTests, skipTests)
      .put(ReleaseKeys.cross, crossEnabled)
      .put(ReleaseKeys.commandLineReleaseVersion, releaseVersionArg)
      .put(ReleaseKeys.commandLineNextVersion, nextVersionArg)
      .put(ReleaseKeys.tagDefault, tagDefaultArg)

    val initialCtx = initialContext(
      decoratedState,
      skipTests = skipTests,
      skipPublish = skipPublish,
      interactive = interactive
    )

    decoratedState.log.info("[release-io] Starting release process...")
    decoratedState.log.info(s"[release-io] ${stepFns.length} steps to execute")
    if (crossEnabled) decoratedState.log.info("[release-io] Cross-build enabled")

    // The resource is acquired once and shared across all steps; it is released after all
    // steps complete (or immediately on failure). Each step function receives the resource T.
    val program = resource.use { t =>
      val steps = stepFns.map(_(t))
      ReleaseStepIO.compose(steps, crossEnabled)(initialCtx)
    }

    // unsafeRunSync() blocks the sbt command thread — unavoidable at the sbt plugin boundary.
    // Inner IO.blocking shifts still dispatch to the cats-effect blocking thread pool correctly.
    val finalCtx = program.unsafeRunSync()

    finalCtx.state.log.info("[release-io] Release completed successfully!")
    finalCtx.state
  }
}

/** Default release plugin using `Unit` as the resource type (no external resource needed).
  * Provides setting keys for configuration from `build.sbt`.
  */
object ReleasePluginIO extends ReleasePluginIOLike[Unit] {

  override def trigger = allRequirements

  override def resource: Resource[IO, Unit] = Resource.unit

  object autoImport extends ReleaseIO
}
