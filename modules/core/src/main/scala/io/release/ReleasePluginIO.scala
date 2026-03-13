package io.release

import cats.effect.unsafe.implicits.global
import cats.effect.{IO, Resource}
import _root_.io.release.internal.{CoreReleasePlan, InternalKeys}
import _root_.io.release.steps.ReleaseSteps
import _root_.io.release.vcs.Vcs
import _root_.io.release.version.Version
import sbt.{internal => _, *}
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

  override def requires: Plugins = sbt.plugins.JvmPlugin

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
    releaseIOProcess                := ReleaseSteps.defaults,
    releaseIOCrossBuild             := false,
    releaseIOSkipPublish            := false,
    releaseIOInteractive            := false,
    releaseIOReadVersion            := ReleaseSteps.defaultReadVersion,
    releaseIOWriteVersion           := ReleaseSteps.defaultWriteVersion(
      releaseIOUseGlobalVersion.value
    ),
    releaseIOVersionFile            := baseDirectory.value / "version.sbt",
    releaseIOUseGlobalVersion       := true,
    releaseIOVcsSign                := false,
    releaseIOVcsSignOff             := false,
    releaseIOIgnoreUntrackedFiles   := false,
    releaseIORuntimeVersion         := {
      val st = sbt.Keys.state.value
      st.get(ReleaseKeys.runtimeVersionOverride).getOrElse(Keys.version.value)
    },
    releaseIOTagName                := s"v${releaseIORuntimeVersion.value}",
    releaseIOTagComment             := s"Releasing ${releaseIORuntimeVersion.value}",
    releaseIOCommitMessage          := s"Setting version to ${releaseIORuntimeVersion.value}",
    releaseIONextCommitMessage      := s"Setting version to ${releaseIORuntimeVersion.value}",
    releaseIOVersionBump            := Version.Bump.default,
    releaseIOVersion                := {
      val bump = releaseIOVersionBump.value
      ver =>
        Version(ver)
          .map { v =>
            bump match {
              case Version.Bump.Next =>
                if (v.isSnapshot) v.withoutSnapshot.render
                else
                  throw new IllegalArgumentException(
                    s"[release-io] Expected snapshot version, got: $ver"
                  )
              case _                 => v.withoutQualifier.render
            }
          }
          .getOrElse(
            throw new IllegalArgumentException(s"[release-io] Cannot parse version: $ver")
          )
    },
    releaseIONextVersion            := {
      val bump = releaseIOVersionBump.value
      ver =>
        Version(ver)
          .map(_.bump(bump).asSnapshot.render)
          .getOrElse(
            throw new IllegalArgumentException(s"[release-io] Cannot parse version: $ver")
          )
    },
    ReleaseIOCompat.snapshotDependenciesSetting,
    releaseIOPublishArtifactsChecks := true,
    releaseIOPublishArtifactsAction := publish.value
  )

  override lazy val projectSettings: Seq[Setting[?]] =
    baseReleaseSettings ++ defaultSettingsValues

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
        .map(ReleaseVersion.apply)
    val nextVersion: Parser[ReleaseArg]    =
      (token("next-version") ~> Space ~> token(NotSpace, "<next version>"))
        .map(NextVersion.apply)
    val tagDefault: Parser[ReleaseArg]     =
      (token("default-tag-exists-answer") ~> Space ~> token(NotSpace, "o|k|a|<tag-name>"))
        .map(TagDefault.apply)

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
        val base = Project.extract(state).get(sbt.Keys.thisProject).base
        Vcs.detect(base).unsafeRunSync()
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
      val useDefaults   = args.contains(WithDefaults)
      val skipTests     = args.contains(SkipTests)
      val crossFromArgs = args.contains(CrossBuild)
      val crossEnabled  = crossBuildEnabled(state) || crossFromArgs
      val skipPublish   = skipPublishEnabled(state)
      val interactive   = interactiveEnabled(state)

      val releaseVersionArg = args.collectFirst { case ReleaseVersion(v) => v }
      val nextVersionArg    = args.collectFirst { case NextVersion(v) => v }
      val tagDefaultArg     = args.collectFirst { case TagDefault(v) => v }

      if (args.count(_.isInstanceOf[ReleaseVersion]) > 1)
        state.log.warn(
          s"[release-io] Multiple release-version args provided; using '${releaseVersionArg.getOrElse("<unknown>")}'"
        )
      if (args.count(_.isInstanceOf[NextVersion]) > 1)
        state.log.warn(
          s"[release-io] Multiple next-version args provided; using '${nextVersionArg.getOrElse("<unknown>")}'"
        )
      if (args.count(_.isInstanceOf[TagDefault]) > 1)
        state.log.warn(
          s"[release-io] Multiple default-tag-exists-answer args provided; using '${tagDefaultArg.getOrElse("<unknown>")}'"
        )

      val cleanState = state
        .remove(ReleaseKeys.runtimeVersionOverride)
        .remove(ReleaseKeys.versions)
        .remove(InternalKeys.executionFlags)
        .remove(InternalKeys.coreReleasePlan)

      val plan         = CoreReleasePlan.build(
        CoreReleasePlan.Inputs(
          useDefaults = useDefaults,
          skipTests = skipTests,
          skipPublish = skipPublish,
          interactive = interactive,
          crossBuild = crossEnabled,
          releaseVersionOverride = releaseVersionArg,
          nextVersionOverride = nextVersionArg,
          tagDefault = tagDefaultArg
        )
      )
      val plannedState = CoreReleasePlan.attach(cleanState, plan)
      val stepFns      = releaseProcess(plannedState)

      val initialCtx = initialContext(
        plannedState,
        skipTests = skipTests,
        skipPublish = skipPublish,
        interactive = interactive
      )

      plannedState.log.info("[release-io] Starting release process...")
      plannedState.log.info(s"[release-io] ${stepFns.length} steps to execute")
      if (crossEnabled) plannedState.log.info("[release-io] Cross-build enabled")

      val program = resource.use { t =>
        val steps = stepFns.map(_(t))
        ReleaseStepIO.compose(steps, crossEnabled)(initialCtx)
      }

      // unsafeRunSync() blocks the sbt command thread — unavoidable at the sbt plugin boundary.
      val finalCtx = program.unsafeRunSync()
      if (finalCtx.failed) {
        val cause = finalCtx.failureCause
          .map(e => Option(e.getMessage).getOrElse(e.toString))
          .getOrElse("unknown error")
        finalCtx.state.log.error(s"[release-io] Release failed: $cause")
        finalCtx.state.fail
      } else {
        finalCtx.state.log.info("[release-io] Release completed successfully!")
        finalCtx.state
      }
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
