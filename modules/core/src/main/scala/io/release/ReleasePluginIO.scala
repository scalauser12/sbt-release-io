package io.release

import cats.effect.IO
import cats.effect.Resource
import io.release.internal.CheckModeOutput
import io.release.internal.CoreHookConfiguration
import io.release.internal.CorePreflight
import io.release.internal.CoreExecutionState
import io.release.internal.CoreReleasePlan
import io.release.internal.ReleaseCommandParsers
import io.release.internal.ReleaseCli
import io.release.internal.ReleaseCommandRunner
import io.release.internal.ReleaseHookCompiler
import io.release.internal.ReleaseLogPrefixes
import io.release.steps.ReleaseSteps
import io.release.steps.StepHelpers
import io.release.vcs.Vcs
import io.release.version.Version
import sbt.Keys.*
import sbt.complete.DefaultParsers.*
import sbt.complete.Parser
import sbt.{internal as _, *}

import scala.annotation.nowarn

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
  * auto-enabled via `allRequirements`, so its keys are already in scope when
  * the custom plugin is enabled.
  */
trait ReleasePluginIOLike[T]
    extends AutoPlugin
    with ReleaseIO
    with PluginLikeSupport[ReleaseStepIO, T] {

  override def requires: Plugins = sbt.plugins.JvmPlugin

  protected def stepName(step: ReleaseStepIO): String = step.name

  /** The resource acquired once for the entire release process and passed to each step. */
  def resource: Resource[IO, T]

  /** The release steps. Reads plain steps from the `releaseIOProcess` setting and lifts
    * each into a resource-ignoring function. Override to append resource-aware steps.
    *
    * Overriding this method opts the plugin into legacy raw-process mode, where the
    * hook/policy settings are ignored and the custom process wiring remains authoritative.
    */
  @deprecated(
    "Prefer `releaseIOEnable*` policies and `releaseIO*Hooks`; overriding `releaseProcess` opts the plugin into legacy raw-process mode.",
    "0.7.0"
  )
  protected def releaseProcess(state: State): Seq[T => ReleaseStepIO] =
    liftSteps(Project.extract(state).get(releaseIOProcess))

  /** Resource-free steps used by `check`.
    *
    * Defaults to the plain configured `releaseIOProcess` so `check` avoids acquiring the plugin
    * resource. Custom plugins can override this to add resource-free preflight equivalents for
    * custom resource-backed steps.
    *
    * Overriding this method opts the plugin into legacy raw-process mode, where the
    * hook/policy settings are ignored and the custom process wiring remains authoritative.
    */
  @deprecated(
    "Prefer `releaseIOEnable*` policies and `releaseIO*Hooks`; overriding `releaseCheckProcess` opts the plugin into legacy raw-process mode.",
    "0.7.0"
  )
  protected def releaseCheckProcess(state: State): Seq[ReleaseStepIO] =
    Project.extract(state).get(releaseIOProcess)

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
    ReleaseIO._releaseIOProcess              := ReleaseSteps.defaults,
    releaseIOCrossBuild                      := false,
    releaseIOSkipPublish                     := false,
    releaseIOInteractive                     := false,
    releaseIOEnableSnapshotDependenciesCheck := true,
    releaseIOEnableRunClean                  := true,
    releaseIOEnableRunTests                  := true,
    releaseIOEnableTagging                   := true,
    releaseIOEnablePublish                   := true,
    releaseIOEnablePush                      := true,
    releaseIOAfterCleanCheckHooks            := Seq.empty,
    releaseIOBeforeVersionResolutionHooks    := Seq.empty,
    releaseIOAfterVersionResolutionHooks     := Seq.empty,
    releaseIOBeforeReleaseVersionWriteHooks  := Seq.empty,
    releaseIOAfterReleaseVersionWriteHooks   := Seq.empty,
    releaseIOBeforeReleaseCommitHooks        := Seq.empty,
    releaseIOAfterReleaseCommitHooks         := Seq.empty,
    releaseIOBeforeTagHooks                  := Seq.empty,
    releaseIOAfterTagHooks                   := Seq.empty,
    releaseIOBeforePublishHooks              := Seq.empty,
    releaseIOAfterPublishHooks               := Seq.empty,
    releaseIOBeforeNextVersionWriteHooks     := Seq.empty,
    releaseIOAfterNextVersionWriteHooks      := Seq.empty,
    releaseIOBeforeNextCommitHooks           := Seq.empty,
    releaseIOAfterNextCommitHooks            := Seq.empty,
    releaseIOBeforePushHooks                 := Seq.empty,
    releaseIOAfterPushHooks                  := Seq.empty,
    releaseIOReadVersion                     := ReleaseSteps.defaultReadVersion,
    releaseIOVersionFileContents             := ReleaseSteps.defaultWriteVersion(
      releaseIOUseGlobalVersion.value
    ),
    releaseIOVersionFile                     := baseDirectory.value / "version.sbt",
    releaseIOUseGlobalVersion                := true,
    releaseIOVcsSign                         := false,
    releaseIOVcsSignOff                      := false,
    releaseIOIgnoreUntrackedFiles            := false,
    releaseIORuntimeVersion                  := {
      if (releaseIOUseGlobalVersion.value) (ThisBuild / Keys.version).value
      else Keys.version.value
    },
    releaseIOTagName                         := s"v${releaseIORuntimeVersion.value}",
    releaseIOTagComment                      := s"Releasing ${releaseIORuntimeVersion.value}",
    releaseIOCommitMessage                   := s"Setting version to ${releaseIORuntimeVersion.value}",
    releaseIONextCommitMessage               := s"Setting version to ${releaseIORuntimeVersion.value}",
    releaseIOVersionBump                     := Version.Bump.default,
    releaseIOVersion                         := {
      val bump = releaseIOVersionBump.value
      ver =>
        Version(ver)
          .map { v =>
            bump match {
              case Version.Bump.Next =>
                if (v.isSnapshot) v.withoutSnapshot.render
                else
                  throw new IllegalArgumentException(
                    s"Expected snapshot version, got: $ver"
                  )
              case _                 => v.withoutQualifier.render
            }
          }
          .getOrElse(
            throw new IllegalArgumentException(s"Cannot parse version: $ver")
          )
    },
    releaseIONextVersion                     := {
      val bump = releaseIOVersionBump.value
      ver =>
        Version(ver)
          .map(_.bump(bump).asSnapshot.render)
          .getOrElse(
            throw new IllegalArgumentException(s"Cannot parse version: $ver")
          )
    },
    ReleaseIOCompat.snapshotDependenciesSetting,
    releaseIOPublishArtifactsChecks          := true,
    releaseIOPublishArtifactsAction          := publish.value
  )

  override lazy val projectSettings: Seq[Setting[?]] =
    baseReleaseSettings ++ defaultSettingsValues

  /** Structured parser for `releaseIO` subcommands and arguments.
    *
    * Emits canonical tokens for the shared CLI decoder so sbt keeps keyword completion while
    * mode and argument decoding stays centralized.
    */
  protected lazy val releaseParser: Parser[Seq[String]] =
    ReleaseCommandParsers.build

  /** The name of the main release command. Override to use a different name
    * when coexisting with [[ReleasePluginIO]].
    */
  protected def commandName: String = "releaseIO"

  /** Setting that registers the release command. Uses [[commandName]]. */
  protected def releaseIOCommand: Setting[?] =
    commands += Command(commandName)(_ => releaseParser)(handleReleaseIO)

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
  ): IO[ReleaseContext] = {
    val maybeVersions = state.get(ReleaseKeys.versions)

    for {
      base     <- IO.blocking(Project.extract(state).get(sbt.Keys.thisProject).base)
      maybeVcs <-
        Vcs.detect(base).handleErrorWith { err =>
          IO.blocking(
            state.log.warn(
              s"${ReleaseLogPrefixes.Core} Failed to detect VCS during initial context hydration: " +
                StepHelpers.errorMessage(err)
            )
          ).as(None)
        }
    } yield ReleaseContext(
      state = state,
      versions = maybeVersions,
      vcs = maybeVcs,
      skipTests = skipTests,
      skipPublish = skipPublish,
      interactive = interactive
    )
  }

  private def logLines(state: State, lines: Seq[String]): IO[Unit] =
    ReleaseCommandRunner.logLines(state, ReleaseLogPrefixes.Core, lines)

  private final case class ResolvedProcessMode(
      releaseSteps: Seq[T => ReleaseStepIO],
      checkSteps: Seq[ReleaseStepIO],
      legacyMode: Boolean,
      legacyReasons: Seq[String]
  )

  private def builtInPluginClass: Class[?] = ReleasePluginIO.getClass

  private def declaresProcessHook(methodName: String): Boolean =
    this.getClass.getDeclaredMethods.exists { method =>
      method.getName == methodName &&
      method.getParameterTypes.toList == List(classOf[State])
    }

  // Scala emits concrete forwarders for trait defaults on plugin objects, so reflective
  // method inspection cannot reliably distinguish inherited defaults from real overrides.
  // Treating custom plugin classes and custom plugin superclasses as legacy mode is
  // conservative, but it avoids silently dropping inherited custom process wiring.
  private def usesCustomProcessHooks: Boolean = {
    val customPluginClass = this.getClass != builtInPluginClass && (
      declaresProcessHook("releaseProcess") || declaresProcessHook("releaseCheckProcess")
    )

    customPluginClass || inheritsCustomProcessHooks
  }

  private def inheritsCustomProcessHooks: Boolean = {
    @scala.annotation.tailrec
    def loop(clazz: Class[?]): Boolean =
      if (clazz == null || clazz == classOf[AutoPlugin] || clazz == classOf[Object]) false
      else if (classOf[ReleasePluginIOLike[?]].isAssignableFrom(clazz)) true
      else loop(clazz.getSuperclass)

    loop(this.getClass.getSuperclass)
  }

  @nowarn("cat=deprecation")
  private def resolveProcessMode(state: State): IO[ResolvedProcessMode] =
    IO.blocking {
      val extracted              = Project.extract(state)
      val configuredRaw          = extracted.get(ReleaseIO._releaseIOProcess)
      val configuredCheck        = releaseCheckProcess(state)
      val configuredRelease      = releaseProcess(state)
      val rawProcessChanged      = configuredRaw != ReleaseSteps.defaults
      val checkProcessChanged    = configuredCheck != configuredRaw
      val processHooksOverridden = usesCustomProcessHooks
      val releaseProcessChanged  =
        configuredRelease.length != configuredRaw.length
      val legacyReasons          =
        Seq(
          if (rawProcessChanged) Some("`releaseIOProcess` differs from defaults") else None,
          if (checkProcessChanged)
            Some("`releaseCheckProcess` differs from the configured raw process")
          else None,
          if (processHooksOverridden)
            Some("plugin overrides `releaseProcess` or `releaseCheckProcess`")
          else None,
          if (releaseProcessChanged)
            Some("`releaseProcess` differs from the configured raw process")
          else None
        ).flatten
      val legacyMode             = legacyReasons.nonEmpty

      if (legacyMode)
        ResolvedProcessMode(
          releaseSteps = configuredRelease,
          checkSteps = configuredCheck,
          legacyMode = true,
          legacyReasons = legacyReasons
        )
      else {
        val compiled = ReleaseHookCompiler.compile(state)

        ResolvedProcessMode(
          releaseSteps = liftSteps(compiled),
          checkSteps = compiled,
          legacyMode = false,
          legacyReasons = legacyReasons
        )
      }
    }

  private def logLegacyModeWarning(
      state: State,
      processMode: ResolvedProcessMode
  ): IO[Unit] =
    if (!processMode.legacyMode) IO.unit
    else
      IO.blocking {
        val reasons = processMode.legacyReasons.mkString("; ")
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

  private final class CoreCommandInputs(
      val cleanState: State,
      val skipTests: Boolean,
      val skipPublish: Boolean,
      val interactive: Boolean,
      val crossEnabled: Boolean,
      val plan: CoreReleasePlan
  )

  private def buildCommandInputs(
      state: State,
      args: Seq[ReleaseCli.Arg],
      warnOnDuplicates: Boolean
  ): CoreCommandInputs = {
    import ReleaseCli.Arg.*

    val useDefaults   = args.contains(WithDefaults)
    val skipTests     = args.contains(SkipTests)
    val crossFromArgs = args.contains(CrossBuild)
    val crossEnabled  = crossBuildEnabled(state) || crossFromArgs
    val skipPublish   = skipPublishEnabled(state)
    val interactive   = interactiveEnabled(state)

    val releaseVersionArg = args.collectFirst { case ReleaseVersion(v) => v }
    val nextVersionArg    = args.collectFirst { case NextVersion(v) => v }
    val tagDefaultArg     = args.collectFirst { case TagDefault(v) => v }

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

    new CoreCommandInputs(
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
          commandName = commandName
        )
      )
    )
  }

  private def handleReleaseIO(state: State, tokens: Seq[String]): State =
    ReleaseCli.parse(tokens, commandName) match {
      case Left(message) =>
        state.log.error(s"${ReleaseLogPrefixes.Core} $message")
        state.fail
      case Right(parsed) =>
        parsed.mode match {
          case ReleaseCli.CommandMode.Help  => doReleaseHelp(state)
          case ReleaseCli.CommandMode.Check => doReleaseCheck(state, parsed.args)
          case ReleaseCli.CommandMode.Run   => doReleaseIO(state, parsed.args)
        }
    }

  protected def doReleaseHelp(state: State): State = {
    val program = logLines(state, CorePreflight.helpLines(commandName))
    ReleaseCommandRunner.runSync(state, ReleaseLogPrefixes.Core)(program.as(state))
  }

  /** Execute the release process: parse arguments, acquire the resource, run all steps.
    * Override [[commandName]] to change the command that invokes this method.
    */
  protected def doReleaseIO(state: State, args: Seq[ReleaseCli.Arg]): State = {
    val inputs  = buildCommandInputs(state, args, warnOnDuplicates = true)
    val program = for {
      process  <- resolveProcessMode(inputs.cleanState)
      _        <- logLegacyModeWarning(inputs.cleanState, process)
      _        <- IO.blocking {
                    inputs.cleanState.log.info(
                      s"${ReleaseLogPrefixes.Core} Starting release process..."
                    )
                    inputs.cleanState.log.info(
                      s"${ReleaseLogPrefixes.Core} ${process.releaseSteps.length} steps to execute"
                    )
                    if (inputs.crossEnabled)
                      inputs.cleanState.log.info(s"${ReleaseLogPrefixes.Core} Cross-build enabled")
                  }
      finalCtx <- resource
                    .use { t =>
                      val steps = process.releaseSteps.map(_(t))
                      initialContext(
                        inputs.cleanState,
                        inputs.skipTests,
                        inputs.skipPublish,
                        inputs.interactive
                      ).flatMap { initialCtx =>
                        ReleaseStepIO.compose(steps, inputs.crossEnabled)(
                          initialCtx.withExecutionState(CoreExecutionState(inputs.plan))
                        )
                      }
                    }
      result   <- ReleaseCommandRunner
                    .handleReleaseResult(finalCtx, ReleaseLogPrefixes.Core)
    } yield result

    // unsafeRunSync() blocks the sbt command thread — unavoidable at the sbt plugin boundary.
    ReleaseCommandRunner.runSync(state, ReleaseLogPrefixes.Core)(program)
  }

  protected def doReleaseCheck(state: State, args: Seq[ReleaseCli.Arg]): State = {
    val inputs = buildCommandInputs(state, args, warnOnDuplicates = false)

    val program = for {
      process <- resolveProcessMode(inputs.cleanState)
      _       <- logLegacyModeWarning(inputs.cleanState, process)
      _       <- CheckModeOutput.logCheckStart(
                   inputs.cleanState,
                   ReleaseLogPrefixes.Core,
                   process.checkSteps.length
                 )
      summary <- initialContext(
                   inputs.cleanState,
                   inputs.skipTests,
                   inputs.skipPublish,
                   inputs.interactive
                 ).flatMap { initialCtx =>
                   CorePreflight.check(
                     initialCtx.withExecutionState(CoreExecutionState(inputs.plan)),
                     process.checkSteps,
                     inputs.crossEnabled
                   )
                 }
      _       <- logLines(inputs.cleanState, CorePreflight.renderSummary(summary))
      _       <- CheckModeOutput.logCheckPassed(inputs.cleanState, ReleaseLogPrefixes.Core)
    } yield inputs.cleanState

    ReleaseCommandRunner.runSync(state, ReleaseLogPrefixes.Core)(program)
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
