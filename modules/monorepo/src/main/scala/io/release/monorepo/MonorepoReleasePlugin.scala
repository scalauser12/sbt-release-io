package io.release.monorepo

import cats.effect.unsafe.implicits.global
import cats.effect.{IO, Resource}
import io.release.ReleasePluginIO
import io.release.monorepo.MonorepoReleaseIO.*
import sbt.*
import sbt.Keys.*
import sbt.Project.extract
import sbt.complete.DefaultParsers.*
import sbt.complete.Parser

import scala.language.implicitConversions
import scala.util.Try
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
  */
trait MonorepoReleasePluginLike[T] extends AutoPlugin {

  override def requires: Plugins = ReleasePluginIO

  /** The resource acquired once for the entire monorepo release process and passed to each step. */
  def resource: Resource[IO, T]

  /** The monorepo release steps. Reads plain steps from the `releaseIOMonorepoProcess` setting
    * and lifts each into a resource-ignoring function. Override to append resource-aware steps.
    */
  protected def monorepoReleaseProcess(state: State): Seq[T => MonorepoStepIO] =
    liftSteps(Project.extract(state).get(releaseIOMonorepoProcess))

  // ── Ergonomic helpers for resource-aware step composition ──────────

  /** Implicitly lifts a plain monorepo step into a resource-ignoring step function.
    * Always in scope inside the plugin trait, so plain steps and resource-aware steps
    * can be freely mixed in the same `Seq[T => MonorepoStepIO]`.
    */
  protected implicit def liftStep(step: MonorepoStepIO): T => MonorepoStepIO =
    (_: T) => step

  /** Lift a sequence of plain monorepo steps into resource-ignoring step functions. */
  protected def liftSteps(steps: Seq[MonorepoStepIO]): Seq[T => MonorepoStepIO] =
    steps.map(liftStep)

  /** Read default steps from settings and append resource-aware steps at the end.
    *
    * {{{
    * override protected def monorepoReleaseProcess(state: State) =
    *   defaultsWith(state)(
    *     (client: HttpClient) => MonorepoStepIO.Global("notify", ctx => IO { ... })
    *   )
    * }}}
    */
  protected def defaultsWith(state: State)(
      extraSteps: (T => MonorepoStepIO)*
  ): Seq[T => MonorepoStepIO] =
    liftSteps(Project.extract(state).get(releaseIOMonorepoProcess)) ++ extraSteps

  /** Read default steps and insert resource-aware steps after a named step.
    *
    * @param afterStep the `name` of the step after which to insert
    * Throws `RuntimeException` if no step with the given name is found.
    */
  protected def defaultsWithAfter(state: State, afterStep: String)(
      extraSteps: (T => MonorepoStepIO)*
  ): Seq[T => MonorepoStepIO] = {
    val defaults        = Project.extract(state).get(releaseIOMonorepoProcess)
    val idx             = defaults.indexWhere(_.name == afterStep)
    if (idx < 0)
      throw new RuntimeException(
        s"Step '$afterStep' not found in defaults. " +
          s"Available: ${defaults.map(_.name).mkString(", ")}"
      )
    val (before, after) = defaults.splitAt(idx + 1)
    liftSteps(before) ++ extraSteps ++ liftSteps(after)
  }

  /** Read default steps and insert resource-aware steps before a named step.
    *
    * @param beforeStep the `name` of the step before which to insert
    * Throws `RuntimeException` if no step with the given name is found.
    */
  protected def defaultsWithBefore(state: State, beforeStep: String)(
      extraSteps: (T => MonorepoStepIO)*
  ): Seq[T => MonorepoStepIO] = {
    val defaults        = Project.extract(state).get(releaseIOMonorepoProcess)
    val idx             = defaults.indexWhere(_.name == beforeStep)
    if (idx < 0)
      throw new RuntimeException(
        s"Step '$beforeStep' not found in defaults. " +
          s"Available: ${defaults.map(_.name).mkString(", ")}"
      )
    val (before, after) = defaults.splitAt(idx)
    liftSteps(before) ++ extraSteps ++ liftSteps(after)
  }

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

    val withDefaults: Parser[MonorepoArg] = token("with-defaults").map(_ => WithDefaults)
    val skipTests: Parser[MonorepoArg]    = token("skip-tests").map(_ => SkipTests)
    val crossBuild: Parser[MonorepoArg]   = token("cross").map(_ => CrossBuild)
    val allChanged: Parser[MonorepoArg]   = token("all-changed").map(_ => AllChanged)

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

      _ <- validate(
             flags.allChanged && selectedNames.nonEmpty,
             "Cannot combine 'all-changed' with explicit project selection. " +
               s"Either use 'all-changed' alone or specify projects explicitly."
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

      unusedOverrides =
        if (selectedNames.nonEmpty)
          (releaseVersionOverrides.keySet ++ nextVersionOverrides.keySet) --
            selectedProjects.map(_.name).toSet
        else Set.empty[String]
      _              <- validate(
                          unusedOverrides.nonEmpty,
                          s"Version overrides target non-selected projects: " +
                            s"${unusedOverrides.mkString(", ")}. " +
                            s"Selected: ${selectedProjects.map(_.name).mkString(", ")}"
                        )

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
            state.log.error(
              s"[release-io-monorepo] Release failed: ${Option(e.getMessage).getOrElse(e.toString)}"
            )
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
  * Then run: `sbt releaseIOMonorepo core with-defaults`
  */
object MonorepoReleasePlugin extends MonorepoReleasePluginLike[Unit] {

  override def trigger = noTrigger

  override def resource: Resource[IO, Unit] = Resource.unit

  object autoImport extends MonorepoReleaseIO {
    val MonorepoTagStrategy = _root_.io.release.monorepo.MonorepoTagStrategy
  }
}
