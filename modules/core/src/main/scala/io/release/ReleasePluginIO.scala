package io.release

import cats.effect.IO
import cats.effect.Resource
import io.release.internal.CoreCommandExecution
import io.release.internal.ReleaseCli
import io.release.internal.ReleaseCommandParsers
import io.release.internal.ReleaseLogPrefixes
import io.release.steps.ReleaseSteps
import io.release.steps.StepHelpers
import io.release.vcs.Vcs
import io.release.version.Version
import sbt.Keys.*
import sbt.complete.Parser
import sbt.{internal as _, *}

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
  * (e.g. `reference to releaseIOBeforeTagHooks is ambiguous`). [[ReleasePluginIO]] is
  * auto-enabled via `allRequirements`, so its keys are already in scope when
  * the custom plugin is enabled.
  */
trait ReleasePluginIOLike[T] extends AutoPlugin with ReleaseIO {

  override def requires: Plugins = sbt.plugins.JvmPlugin

  /** The resource acquired once for the entire release process and passed to each step. */
  def resource: Resource[IO, T]

  /** Resource-aware hooks compiled into the normal hook/policy lifecycle for this custom plugin.
    *
    * Use this when the built-in lifecycle points are sufficient but the hook logic needs the
    * shared plugin resource. Overriding this method keeps the plugin on compiled hook mode:
    * `check` runs only the resource-free `validate` functions, while `run` acquires [[resource]]
    * and runs both validation and execution.
    */
  protected def releaseResourceHooks(state: State): ReleaseResourceHooks[T] =
    ReleaseResourceHooks.empty

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

  /** Base settings that include all default `releaseIO*` values plus command registration.
    * Custom plugins that override `projectSettings` should start from `baseReleaseSettings`
    * so the release command and required default keys stay defined.
    */
  protected def baseReleaseSettings: Seq[Setting[?]] =
    defaultSettingsValues ++ Seq(releaseIOCommand)

  /** Default values for the release-io setting keys. */
  protected def defaultSettingsValues: Seq[Setting[?]] = Seq(
    releaseIOCrossBuild                        := false,
    releaseIOSkipPublish                       := false,
    releaseIOInteractive                       := false,
    releaseIODefaultTagExistsAnswer            := None,
    releaseIODefaultSnapshotDependenciesAnswer := None,
    releaseIODefaultRemoteCheckFailureAnswer   := None,
    releaseIODefaultUpstreamBehindAnswer       := None,
    releaseIODefaultPushAnswer                 := None,
    releaseIOEnableSnapshotDependenciesCheck   := true,
    releaseIOEnableRunClean                    := true,
    releaseIOEnableRunTests                    := true,
    releaseIOEnableTagging                     := true,
    releaseIOEnablePublish                     := true,
    releaseIOEnablePush                        := true,
    releaseIOAfterCleanCheckHooks              := Seq.empty,
    releaseIOBeforeVersionResolutionHooks      := Seq.empty,
    releaseIOAfterVersionResolutionHooks       := Seq.empty,
    releaseIOBeforeReleaseVersionWriteHooks    := Seq.empty,
    releaseIOAfterReleaseVersionWriteHooks     := Seq.empty,
    releaseIOBeforeReleaseCommitHooks          := Seq.empty,
    releaseIOAfterReleaseCommitHooks           := Seq.empty,
    releaseIOBeforeTagHooks                    := Seq.empty,
    releaseIOAfterTagHooks                     := Seq.empty,
    releaseIOBeforePublishHooks                := Seq.empty,
    releaseIOAfterPublishHooks                 := Seq.empty,
    releaseIOBeforeNextVersionWriteHooks       := Seq.empty,
    releaseIOAfterNextVersionWriteHooks        := Seq.empty,
    releaseIOBeforeNextCommitHooks             := Seq.empty,
    releaseIOAfterNextCommitHooks              := Seq.empty,
    releaseIOBeforePushHooks                   := Seq.empty,
    releaseIOAfterPushHooks                    := Seq.empty,
    releaseIOReadVersion                       := ReleaseSteps.defaultReadVersion,
    releaseIOVersionFileContents               := ReleaseSteps.defaultWriteVersion(
      releaseIOUseGlobalVersion.value
    ),
    releaseIOVersionFile                       := baseDirectory.value / "version.sbt",
    releaseIOUseGlobalVersion                  := true,
    releaseIOVcsSign                           := false,
    releaseIOVcsSignOff                        := false,
    releaseIOIgnoreUntrackedFiles              := false,
    releaseIOInternalReleaseHash               := None,
    releaseIOInternalReleaseTag                := None,
    packageOptions ++= ReleaseIO.releaseManifestPackageOptions(
      releaseIOInternalReleaseHash.value,
      releaseIOInternalReleaseTag.value
    ),
    releaseIOVcsRemoteCheckTimeout             := scala.concurrent.duration.DurationInt(60).seconds,
    releaseIORuntimeVersion                    := {
      if (releaseIOUseGlobalVersion.value) (ThisBuild / Keys.version).value
      else Keys.version.value
    },
    releaseIOTagName                           := s"v${releaseIORuntimeVersion.value}",
    releaseIOTagComment                        := s"Releasing ${releaseIORuntimeVersion.value}",
    releaseIOCommitMessage                     := s"Setting version to ${releaseIORuntimeVersion.value}",
    releaseIONextCommitMessage                 := s"Setting version to ${releaseIORuntimeVersion.value}",
    releaseIOVersionBump                       := Version.Bump.default,
    releaseIOVersion                           := {
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
    releaseIONextVersion                       := {
      val bump = releaseIOVersionBump.value
      ver =>
        Version(ver)
          .map(_.bump(bump).asSnapshot.render)
          .getOrElse(
            throw new IllegalArgumentException(s"Cannot parse version: $ver")
          )
    },
    ReleaseIOCompat.snapshotDependenciesSetting,
    releaseIOPublishArtifactsChecks            := true,
    releaseIOPublishArtifactsAction            := publish.value
  )

  override lazy val projectSettings: Seq[Setting[?]] =
    baseReleaseSettings

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

  @scala.annotation.nowarn("cat=deprecation")
  private[release] final def commandRuntime: CoreCommandExecution.CommandRuntime[T] =
    CoreCommandExecution.CommandRuntime(
      commandName = commandName,
      resource = resource,
      resolveResourceHooks = state => releaseResourceHooks(state),
      resolveCrossBuildEnabled = state => crossBuildEnabled(state),
      resolveSkipPublishEnabled = state => skipPublishEnabled(state),
      resolveInteractiveEnabled = state => interactiveEnabled(state),
      initialContext = (state, skipTests, skipPublish, interactive) =>
        this.initialContext(state, skipTests, skipPublish, interactive)
    )

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

  protected def doReleaseHelp(state: State): State =
    CoreCommandExecution.doHelp(state, commandName)

  /** Execute the release process: parse arguments, acquire the resource, run all steps.
    * Override [[commandName]] to change the command that invokes this method.
    */
  protected def doReleaseIO(state: State, args: Seq[ReleaseCli.Arg]): State =
    CoreCommandExecution.doRelease(state, args, commandRuntime)

  protected def doReleaseCheck(state: State, args: Seq[ReleaseCli.Arg]): State =
    CoreCommandExecution.doCheck(state, args, commandRuntime)
}

/** Default release plugin using `Unit` as the resource type (no external resource needed).
  * Exposes setting keys to `build.sbt` via `autoImport`.
  */
object ReleasePluginIO extends ReleasePluginIOLike[Unit] {

  override def trigger = allRequirements

  override def resource: Resource[IO, Unit] = Resource.unit

  object autoImport extends ReleaseIO
}
