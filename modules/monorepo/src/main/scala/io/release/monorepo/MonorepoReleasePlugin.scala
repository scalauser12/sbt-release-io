package io.release.monorepo

import scala.util.Try
import scala.util.control.NonFatal

import cats.effect.{IO, Resource}
import cats.effect.unsafe.implicits.global
import io.release.ReleasePluginIO
import io.release.monorepo.MonorepoReleaseIO.*
import sbt.*
import sbt.Keys.*
import sbt.Project.extract
import sbt.complete.DefaultParsers.*
import sbt.complete.Parser

/** Base trait for resource-parameterized monorepo release plugins. Each release step
  * is a function `T => MonorepoStepIO` where `T` is a resource acquired once for the
  * entire release process.
  *
  * Plain `MonorepoStepIO` values are implicitly lifted to `T => MonorepoStepIO` via [[liftStep]].
  * A release command (named by [[commandName]]) and default settings are registered automatically.
  *
  * To coexist with the default [[MonorepoReleasePlugin]], use `noTrigger` and override
  * [[commandName]]:
  * {{{
  * object MyMonorepoRelease extends MonorepoReleasePluginLike[HttpClient] {
  *   override def trigger     = noTrigger
  *   override def commandName = "releaseMonorepoCustom"
  *   override def resource    = Resource.make(IO(new HttpClient()))(c => IO(c.close()))
  *   override def additionalSteps = Seq(client => notifySlack(client))
  *
  *   object autoImport extends MonorepoReleaseIO
  * }
  * // In build.sbt: enablePlugins(MyMonorepoRelease)
  * // Run with:     sbt releaseMonorepoCustom core api with-defaults
  * }}}
  */
trait MonorepoReleasePluginLike[T] extends AutoPlugin {
  import scala.language.implicitConversions

  override def requires: Plugins = ReleasePluginIO

  /** The resource acquired once for the entire monorepo release process and passed to each step. */
  def resource: Resource[IO, T]

  /** The monorepo release steps. Each step is a function from the resource `T` to a
    * `MonorepoStepIO`. Plain `MonorepoStepIO` values are implicitly lifted via [[liftStep]].
    * Defaults to reading from the `releaseIOMonorepoProcess` setting, plus [[additionalSteps]].
    */
  protected def monorepoReleaseProcess(state: State): Seq[T => MonorepoStepIO] =
    Project.extract(state).get(releaseIOMonorepoProcess).map(liftStep) ++ additionalSteps

  /** Resource-parameterized steps appended to the monorepo release process.
    * Override to add steps that use the resource `T` without overriding `monorepoReleaseProcess`.
    * For insertion at specific positions, override `monorepoReleaseProcess` directly.
    */
  protected def additionalSteps: Seq[T => MonorepoStepIO] = Seq.empty

  /** Implicitly lift a plain `MonorepoStepIO` to a resource-ignoring step.
    * This allows mixing plain steps and resource-parameterized steps in `monorepoReleaseProcess`.
    */
  protected implicit def liftStep(step: MonorepoStepIO): T => MonorepoStepIO = _ => step

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
  }

  // ── Parser ──────────────────────────────────────────────────────────

  /** Parser for monorepo release command arguments with tab completion. */
  protected lazy val monorepoParser: Parser[Seq[MonorepoArg]] = {
    import MonorepoArg.*

    val withDefaults: Parser[MonorepoArg]                                                          = token("with-defaults").map(_ => WithDefaults)
    val skipTests: Parser[MonorepoArg]                                                             = token("skip-tests").map(_ => SkipTests)
    val crossBuild: Parser[MonorepoArg]                                                            = token("cross").map(_ => CrossBuild)
    val allChanged: Parser[MonorepoArg]                                                            = token("all-changed").map(_ => AllChanged)
    def versionParser(keyword: String, ctor: (String, String) => MonorepoArg): Parser[MonorepoArg] =
      (token(keyword) ~> Space ~> token(NotSpace, "<project>=<version>")).map { s =>
        val parts = s.split("=", 2)
        ctor(parts(0), parts.lift(1).getOrElse(""))
      }

    val releaseVer                       = versionParser("release-version", ReleaseVersion.apply)
    val nextVer                          = versionParser("next-version", NextVersion.apply)
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

  // ── Project resolution ────────────────────────────────────────────

  /** Resolve all monorepo projects from sbt settings.
    * Wrapped in `Try` because `versionFileFn(ref)` can throw for misconfigured projects.
    */
  private def resolveProjects(
      extracted: Extracted,
      releaseVersionOverrides: Map[String, String],
      nextVersionOverrides: Map[String, String]
  ): Either[Throwable, Seq[ProjectReleaseInfo]] =
    Try {
      val allProjectRefs = extracted.get(releaseIOMonorepoProjects)
      val versionFileFn  = extracted.get(releaseIOMonorepoVersionFile)
      allProjectRefs.map { ref =>
        val projBase =
          (ref / baseDirectory).get(extracted.structure.data).getOrElse {
            throw new RuntimeException(
              s"Cannot resolve baseDirectory for project '${ref.project}'. " +
                "Ensure the project is correctly defined in the build."
            )
          }
        val projName = ref.project
        ProjectReleaseInfo(
          ref = ref,
          name = projName,
          baseDir = projBase,
          versionFile = versionFileFn(ref),
          versions = {
            val rel  = releaseVersionOverrides.getOrElse(projName, "")
            val next = nextVersionOverrides.getOrElse(projName, "")
            if (rel.nonEmpty || next.nonEmpty) Some((rel, next)) else None
          }
        )
      }
    }.toEither

  // ── Argument validation ───────────────────────────────────────────

  /** Validate parsed arguments and resolve projects. Returns the selected projects and flags,
    * or a failed `State` on validation error.
    */
  private def validateArgs(
      state: State,
      args: Seq[MonorepoArg]
  ): Either[State, (Seq[ProjectReleaseInfo], ReleaseFlags)] = {
    import MonorepoArg.*

    def fail(msg: String): State = {
      state.log.error(s"[release-io-monorepo] $msg")
      state.fail
    }

    def validate(condition: Boolean, msg: => String): Either[State, Unit] =
      if (condition) Left(fail(msg)) else Right(())

    val extracted               = extract(state)
    val flags                   = parseFlags(args, extracted)
    val selectedNames           = args.collect { case SelectProject(name) => name }
    val releaseVersionOverrides = args.collect { case ReleaseVersion(p, v) => p -> v }.toMap
    val nextVersionOverrides    = args.collect { case NextVersion(p, v) => p -> v }.toMap

    for {
      _ <- validate(
             releaseVersionOverrides.exists { case (p, v) => p.isEmpty || v.isEmpty },
             "Invalid release-version format. Expected project=version"
           )
      _ <- validate(
             nextVersionOverrides.exists { case (p, v) => p.isEmpty || v.isEmpty },
             "Invalid next-version format. Expected project=version"
           )

      allProjects <- resolveProjects(extracted, releaseVersionOverrides, nextVersionOverrides).left
                       .map(e => fail(e.getMessage))

      validNames       = allProjects.map(_.name).toSet
      invalidOverrides =
        (releaseVersionOverrides.keySet ++ nextVersionOverrides.keySet) -- validNames

      _ <- validate(
             invalidOverrides.nonEmpty,
             s"Unknown projects in version overrides: " +
               s"${invalidOverrides.mkString(", ")}. Available: ${validNames.mkString(", ")}"
           )
      _ <- validate(
             extracted.get(releaseIOMonorepoUseGlobalVersion) && selectedNames.nonEmpty &&
               selectedNames.toSet != validNames,
             s"Global version mode is active — all projects share a single " +
               s"version file. Selecting a subset of projects (${selectedNames.mkString(", ")}) is " +
               s"not supported. Release all projects or disable releaseIOMonorepoUseGlobalVersion."
           )

      invalid = selectedNames.filterNot(validNames.contains)
      _      <- validate(
                  selectedNames.nonEmpty && invalid.nonEmpty,
                  s"Unknown projects: ${invalid.mkString(", ")}. " +
                    s"Available: ${validNames.mkString(", ")}"
                )

      selectedProjects =
        if (selectedNames.nonEmpty) allProjects.filter(p => selectedNames.contains(p.name))
        else allProjects

      _ <- validate(
             selectedProjects.isEmpty,
             s"No projects selected for monorepo release. " +
               s"Available: ${validNames.mkString(", ")}"
           )
    } yield (selectedProjects, flags)
  }

  // ── Context building ──────────────────────────────────────────────

  private def buildContext(
      state: State,
      projects: Seq[ProjectReleaseInfo],
      flags: ReleaseFlags,
      args: Seq[MonorepoArg]
  ): MonorepoContext = {
    import MonorepoArg.*
    val selectedNames = args.collect { case SelectProject(name) => name }
    MonorepoContext(
      state = state,
      projects = projects,
      skipTests = flags.skipTests,
      skipPublish = flags.skipPublish,
      interactive = flags.interactive && !flags.useDefaults,
      tagStrategy = flags.tagStrategy,
      attributes =
        if (selectedNames.nonEmpty) Map("projects-selected" -> "true")
        else if (flags.allChanged) Map("all-changed" -> "true")
        else Map.empty
    )
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
    validateArgs(state, args) match {
      case Left(failedState)                => failedState
      case Right((selectedProjects, flags)) =>
        try {
          // monorepoReleaseProcess reads from sbt settings — can throw on misconfiguration.
          val stepFns    = monorepoReleaseProcess(state)
          val initialCtx = buildContext(state, selectedProjects, flags, args)

          logReleaseStart(state, stepFns.length, selectedProjects.length, flags)

          // The resource is acquired once and shared across all steps; it is released after all
          // steps complete (or immediately on failure). Each step function receives the resource T.
          val program = resource.use { t =>
            val steps = stepFns.map(_(t))
            MonorepoStepIO.compose(steps, flags.crossBuild)(initialCtx)
          }

          // unsafeRunSync() blocks the sbt command thread — unavoidable at the sbt plugin boundary.
          val finalCtx = program.unsafeRunSync()
          finalCtx.state.log.info(
            "[release-io-monorepo] Monorepo release completed successfully!"
          )
          finalCtx.state
        } catch {
          case NonFatal(e) =>
            state.log.error(s"[release-io-monorepo] Release failed: ${e.getMessage}")
            state.fail
        }
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
  * Then run: `sbt releaseIOMonorepo core api with-defaults`
  */
object MonorepoReleasePlugin extends MonorepoReleasePluginLike[Unit] {

  override def trigger = noTrigger

  override def resource: Resource[IO, Unit] = Resource.unit

  object autoImport extends MonorepoReleaseIO {
    val MonorepoTagStrategy = _root_.io.release.monorepo.MonorepoTagStrategy
  }
}
