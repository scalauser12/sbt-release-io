package io.release.monorepo

import cats.effect.unsafe.implicits.global
import cats.effect.{IO, Resource}
import io.release.{PluginLikeSupport, ReleaseKeys, ReleasePluginIO}
import io.release.internal.{ExecutionFlags, InternalKeys}
import io.release.monorepo.{MonorepoTagStrategy as MonorepoTagStrategy_}
import io.release.monorepo.internal.{MonorepoProjectResolver, MonorepoReleasePlan}
import sbt.Keys.*
import sbt.complete.DefaultParsers.*
import sbt.complete.Parser
import sbt.{internal as _, *}

import scala.language.implicitConversions
import scala.util.control.NonFatal

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
      commands += Command(commandName)(_ => monorepoParser)(doMonorepoRelease)
    )

  // ── Command-line argument types ─────────────────────────────────────

  protected sealed trait MonorepoArg
  protected object MonorepoArg {
    case object WithDefaults                                    extends MonorepoArg
    case object SkipTests                                       extends MonorepoArg
    case object CrossBuild                                      extends MonorepoArg
    case object AllChanged                                      extends MonorepoArg
    case class SelectProject(name: String)                      extends MonorepoArg
    case class ReleaseVersion(project: String, version: String) extends MonorepoArg
    case class NextVersion(project: String, version: String)    extends MonorepoArg
    case class GlobalReleaseVersion(version: String)            extends MonorepoArg
    case class GlobalNextVersion(version: String)               extends MonorepoArg
  }

  // ── Parser ──────────────────────────────────────────────────────────

  /** Parser for monorepo release command arguments with tab completion. */
  protected lazy val monorepoParser: Parser[Seq[MonorepoArg]] = {
    import MonorepoArg.*

    val withDefaults: Parser[MonorepoArg] = token("with-defaults").map(_ => WithDefaults)
    val skipTests: Parser[MonorepoArg]    = token("skip-tests").map(_ => SkipTests)
    val crossBuild: Parser[MonorepoArg]   = token("cross").map(_ => CrossBuild)
    val allChanged: Parser[MonorepoArg]   = token("all-changed").map(_ => AllChanged)

    def versionParser(
        keyword: String,
        perProject: (String, String) => MonorepoArg,
        global: String => MonorepoArg
    ): Parser[MonorepoArg] =
      (token(keyword) ~> Space ~> token(NotSpace, "<version> or <project>=<version>")).map { s =>
        val parts = s.split("=", 2)
        if (parts.length == 2) perProject(parts(0), parts(1))
        else global(s)
      }

    val releaseVer                       = versionParser(
      "release-version",
      ReleaseVersion.apply,
      GlobalReleaseVersion.apply
    )
    val nextVer                          = versionParser("next-version", NextVersion.apply, GlobalNextVersion.apply)
    val projectName: Parser[MonorepoArg] =
      token(NotSpace, "<project>").map(SelectProject(_))

    // projectName must be last — it's the catch-all
    val arg =
      withDefaults | skipTests | crossBuild | allChanged | releaseVer | nextVer | projectName
    (Space ~> arg).*
  }

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

  private def parseFlags(args: Seq[MonorepoArg], extracted: Extracted): ReleaseFlags = {
    import MonorepoArg.*
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
      args: Seq[MonorepoArg],
      flags: ReleaseFlags
  ): MonorepoReleasePlan.Inputs = {
    import MonorepoArg.*

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
      globalNextVersions = args.collect { case GlobalNextVersion(v) => v }
    )
  }

  // ── Context building ──────────────────────────────────────────────

  private def buildContext(
      state: State,
      flags: ReleaseFlags,
      plan: MonorepoReleasePlan
  ): IO[MonorepoContext] =
    IO.blocking(MonorepoRuntime.fromState(state)).flatMap { runtime =>
      MonorepoProjectResolver.resolveAll(state).map { projects =>
        MonorepoContext(
          state = state,
          projects = MonorepoProjectResolver.applyVersionOverrides(
            projects,
            plan,
            runtime.useGlobalVersion
          ),
          skipTests = plan.flags.skipTests,
          skipPublish = plan.flags.skipPublish,
          interactive = plan.flags.interactive,
          tagStrategy = flags.tagStrategy
        ).withReleasePlan(plan)
      }
    }

  private def logReleaseStart(
      state: State,
      stepCount: Int,
      projectCount: Int,
      flags: ReleaseFlags
  ): Unit = {
    state.log.info("[release-io-monorepo] Starting monorepo release...")
    state.log.info(s"[release-io-monorepo] $stepCount steps, $projectCount project(s)")
    if (flags.skipTests) state.log.info("[release-io-monorepo] Tests will be skipped")
    if (flags.skipPublish) state.log.info("[release-io-monorepo] Publish will be skipped")
  }

  // ── Release execution ───────────────────────────────────────────────

  protected def doMonorepoRelease(state: State, args: Seq[MonorepoArg]): State =
    try {
      val extracted = Project.extract(state)
      val flags     = parseFlags(args, extracted)

      val cleanState = state
        .remove(ReleaseKeys.versions)
        .remove(InternalKeys.executionFlags)
        .remove(InternalKeys.coreReleasePlan)
        .remove(internal.MonorepoReleasePlan.globalVersionWrittenKey)

      val program: IO[State] = for {
        plannedEither <- MonorepoReleasePlan.build(cleanState, plannerInputs(args, flags))
        result        <- plannedEither match {
                           case Left(failedState) => IO.pure(failedState)
                           case Right(plan)       =>
                             val plannedState =
                               cleanState.put(
                                 InternalKeys.executionFlags,
                                 plan.flags
                               )
                             val stepFns      = monorepoReleaseProcess(plannedState)
                             for {
                               initialCtx <- buildContext(plannedState, flags, plan)
                               _          <- IO.blocking(
                                               logReleaseStart(
                                                 plannedState,
                                                 stepFns.length,
                                                 initialCtx.projects.length,
                                                 flags
                                               )
                                             )
                               finalCtx   <- resource.use { t =>
                                               val steps = stepFns.map(_(t))
                                               MonorepoStepIO.compose(steps, flags.crossBuild)(
                                                 initialCtx
                                               )
                                             }
                               result     <- if (finalCtx.failed) {
                                               val cause = finalCtx.failureCause
                                                 .map(e => Option(e.getMessage).getOrElse(e.toString))
                                                 .getOrElse("unknown error")
                                               IO.blocking(
                                                 finalCtx.state.log.error(
                                                   s"[release-io-monorepo] Release failed: $cause"
                                                 )
                                               ).as(finalCtx.state.fail)
                                             } else {
                                               IO.blocking(
                                                 finalCtx.state.log.info(
                                                   "[release-io-monorepo] Monorepo release completed successfully!"
                                                 )
                                               ).as(finalCtx.state)
                                             }
                             } yield result
                         }
      } yield result

      // unsafeRunSync() blocks the sbt command thread — unavoidable at the sbt plugin boundary.
      program.unsafeRunSync()
    } catch {
      case NonFatal(e) =>
        state.log.error(
          s"[release-io-monorepo] Release failed: ${Option(e.getMessage).getOrElse(e.toString)}"
        )
        state.fail
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
