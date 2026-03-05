package io.release

import cats.effect.unsafe.implicits.global
import cats.effect.{IO, Resource}
import io.release.steps.ReleaseSteps
import sbt.*
import sbt.Keys.*
import sbt.complete.DefaultParsers.*
import sbt.complete.Parser

import scala.language.implicitConversions

/** Base trait for resource-parameterized release plugins. Each release step is a function
  * `T => ReleaseStepIO` where `T` is a resource acquired once for the entire release process.
  *
  * A release command (named by [[commandName]]) and default settings are registered automatically.
  *
  * To coexist with the default [[ReleasePluginIO]], use `noTrigger` and override [[commandName]]:
  * {{{
  * object MyReleasePlugin extends ReleasePluginIOLike[HttpClient] {
  *   override def trigger       = noTrigger
  *   override def commandName   = "releaseCustom"
  *   override def resource      = Resource.make(IO(new HttpClient()))(c => IO(c.close()))
  * }
  * // In build.sbt: enablePlugins(MyReleasePlugin)
  * // Run with:     sbt releaseCustom
  * }}}
  *
  * '''Do not add `object autoImport`''' to custom plugins. When both [[ReleasePluginIO]]
  * and a custom plugin define autoImport, the build gets ambiguous references
  * (e.g. `reference to releaseIOProcess is ambiguous`). [[ReleasePluginIO]] is
  * auto-enabled via `allRequirements`, so its keys are in scope when the custom
  * plugin is enabled (it requires [[ReleasePluginIO]]).
  */
trait ReleasePluginIOLike[T]
    extends AutoPlugin
    with ReleaseIO
    with PluginLikeSupport[ReleaseStepIO, T] {

  override def requires: Plugins = sbtrelease.ReleasePlugin

  protected def stepName(step: ReleaseStepIO): String = step.name

  /** The resource acquired once for the entire release process and passed to each step. */
  def resource: Resource[IO, T]

  /** The release steps. Reads plain steps from the `releaseIOProcess` setting and lifts
    * each into a resource-ignoring function. Override to append resource-aware steps.
    */
  protected def releaseProcess(state: State): Seq[T => ReleaseStepIO] =
    liftSteps(Project.extract(state).get(releaseIOProcess))

  /** Read default steps from settings and append resource-aware steps at the end.
    *
    * {{{
    * override protected def releaseProcess(state: State) =
    *   defaultsWith(state)(
    *     (client: HttpClient) => notifySlack(client)
    *   )
    * }}}
    */
  protected def defaultsWith(state: State)(
      extraSteps: (T => ReleaseStepIO)*
  ): Seq[T => ReleaseStepIO] =
    liftSteps(Project.extract(state).get(releaseIOProcess)) ++ extraSteps

  /** Read default steps and insert resource-aware steps after a named step. */
  protected def defaultsWithAfter(state: State, afterStep: String)(
      extraSteps: (T => ReleaseStepIO)*
  ): Seq[T => ReleaseStepIO] =
    insertAfter(Project.extract(state).get(releaseIOProcess), afterStep)(extraSteps)

  /** Read default steps and insert resource-aware steps before a named step. */
  protected def defaultsWithBefore(state: State, beforeStep: String)(
      extraSteps: (T => ReleaseStepIO)*
  ): Seq[T => ReleaseStepIO] =
    insertBefore(Project.extract(state).get(releaseIOProcess), beforeStep)(extraSteps)

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

  /** Base settings that include command registration. Custom plugins that override
    * `projectSettings` should include `baseReleaseSettings` in their sequence.
    */
  protected def baseReleaseSettings: Seq[Setting[?]] = Seq(releaseIOCommand)

  /** Default values for the release-io setting keys. */
  protected def defaultSettingsValues: Seq[Setting[?]] = Seq(
    releaseIOProcess      := ReleaseSteps.defaults,
    releaseIOCrossBuild   := false,
    releaseIOSkipPublish  := false,
    releaseIOInteractive  := false,
    releaseIOReadVersion  := ReleaseSteps.defaultReadVersion,
    releaseIOWriteVersion := ReleaseSteps.defaultWriteVersion(
      sbtrelease.ReleasePlugin.autoImport.releaseUseGlobalVersion.value
    )
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

  /** The name of the main release command. Override to use a different name
    * when coexisting with [[ReleasePluginIO]].
    */
  protected def commandName: String = "releaseIO"

  /** Setting that registers the release command. Uses [[commandName]]. */
  protected def releaseIOCommand: Setting[?] =
    commands += Command(commandName)(_ => releaseParser)(doReleaseIO)

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

  /** Execute the release process: parse arguments, acquire the resource, run all steps.
    * Override [[commandName]] to change the command that invokes this method.
    */
  protected def doReleaseIO(state: State, args: Seq[ReleaseArg]): State = {
    import ReleaseArg.*
    import scala.util.control.NonFatal

    try {
      val stepFns = releaseProcess(state)

      val useDefaults   = args.contains(WithDefaults)
      val skipTests     = args.contains(SkipTests)
      val crossFromArgs = args.contains(CrossBuild)
      val crossEnabled  = crossBuildEnabled(state) || crossFromArgs
      val skipPublish   = skipPublishEnabled(state)
      val interactive   = interactiveEnabled(state)

      val releaseVersionArg = args.collectFirst { case ReleaseVersion(v) => v }
      val nextVersionArg    = args.collectFirst { case NextVersion(v) => v }
      val tagDefaultArg     = args.collectFirst { case TagDefault(v) => v }

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

      val program = resource.use { t =>
        val steps = stepFns.map(_(t))
        ReleaseStepIO.compose(steps, crossEnabled)(initialCtx)
      }

      // unsafeRunSync() blocks the sbt command thread — unavoidable at the sbt plugin boundary.
      val finalCtx = program.unsafeRunSync()
      finalCtx.state.log.info("[release-io] Release completed successfully!")
      finalCtx.state
    } catch {
      case NonFatal(e) =>
        state.log.error(
          s"[release-io] Release failed: ${Option(e.getMessage).getOrElse(e.toString)}"
        )
        state.fail
    }
  }
}

/** Default release plugin using `Unit` as the resource type (no external resource needed).
  * Exposes setting keys to `build.sbt` via `autoImport`.
  */
object ReleasePluginIO extends ReleasePluginIOLike[Unit] {

  override def trigger = allRequirements

  override def resource: Resource[IO, Unit] = Resource.unit

  object autoImport extends ReleaseIO
}
