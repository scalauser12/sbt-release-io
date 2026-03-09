package io.release.monorepo

import scala.language.implicitConversions
import scala.util.Try
import scala.util.control.NonFatal

import cats.effect.unsafe.implicits.global
import cats.effect.{IO, Resource}
import sbt.*
import sbt.Keys.*
import sbt.complete.DefaultParsers.*
import sbt.complete.Parser

import _root_.io.release.ReleaseKeys
import _root_.io.release.ReleasePluginIO
import _root_.io.release.monorepo.MonorepoReleaseIO.*

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
    with _root_.io.release.PluginLikeSupport[MonorepoStepIO, T] {

  override def requires: Plugins = ReleasePluginIO

  protected def stepName(step: MonorepoStepIO): String = step.name

  /** The resource acquired once for the entire monorepo release process and passed to each step. */
  def resource: Resource[IO, T]

  /** The monorepo release steps. Reads plain steps from the `releaseIOMonorepoProcess` setting
    * and lifts each into a resource-ignoring function. Override to append resource-aware steps.
    */
  protected def monorepoReleaseProcess(state: State): Seq[T => MonorepoStepIO] =
    liftSteps(Project.extract(state).get(releaseIOMonorepoProcess))

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

  /** Read default steps and insert resource-aware steps after a named step. */
  protected def defaultsWithAfter(state: State, afterStep: String)(
      extraSteps: (T => MonorepoStepIO)*
  ): Seq[T => MonorepoStepIO] =
    insertAfter(Project.extract(state).get(releaseIOMonorepoProcess), afterStep)(extraSteps)

  /** Read default steps and insert resource-aware steps before a named step. */
  protected def defaultsWithBefore(state: State, beforeStep: String)(
      extraSteps: (T => MonorepoStepIO)*
  ): Seq[T => MonorepoStepIO] =
    insertBefore(Project.extract(state).get(releaseIOMonorepoProcess), beforeStep)(extraSteps)

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

  // ── Project resolution ────────────────────────────────────────────

  /** Resolve all monorepo projects from sbt settings.
    * Wrapped in `Try` because custom version-file resolvers can throw.
    */
  private def resolveProjects(
      state: State,
      extracted: Extracted,
      releaseVersionOverrides: Map[String, String],
      nextVersionOverrides: Map[String, String],
      globalReleaseVersion: Option[String] = None,
      globalNextVersion: Option[String] = None,
      useGlobalVersion: Boolean = false
  ): Either[Throwable, Seq[ProjectReleaseInfo]] =
    Try {
      val allProjectRefs = extracted.get(releaseIOMonorepoProjects)

      def resolveVersions(projName: String): Option[(String, String)] = {
        val rel  = globalReleaseVersion.getOrElse(releaseVersionOverrides.getOrElse(projName, ""))
        val next = globalNextVersion.getOrElse(nextVersionOverrides.getOrElse(projName, ""))
        if (rel.nonEmpty || next.nonEmpty) Some((rel, next)) else None
      }

      allProjectRefs.map { ref =>
        val projBase =
          (ref / baseDirectory).get(extracted.structure.data).getOrElse {
            throw new IllegalStateException(
              s"Cannot resolve baseDirectory for project '${ref.project}'. " +
                "Ensure the project is correctly defined in the build."
            )
          }
        val projName = ref.project
        ProjectReleaseInfo(
          ref = ref,
          name = projName,
          baseDir = projBase,
          versionFile = MonorepoVersionFiles.resolve(extracted, state, ref, useGlobalVersion),
          versions = resolveVersions(projName)
        )
      }
    }.toEither

  // ── Argument validation ───────────────────────────────────────────

  /** Validate parsed arguments and resolve projects. Returns the selected projects, flags,
    * and selected project names, or a failed `State` on validation error.
    */
  private def validateArgs(
      state: State,
      args: Seq[MonorepoArg]
  ): Either[State, (Seq[ProjectReleaseInfo], ReleaseFlags, Seq[String])] = {
    import MonorepoArg.*

    def failWith(msg: String): State = {
      state.log.error(s"[release-io-monorepo] $msg")
      state.fail
    }

    /** Fails with msg when condition is true. Use for error conditions. */
    def failWhen(condition: Boolean, msg: => String): Either[State, Unit] =
      if (condition) Left(failWith(msg)) else Right(())

    val extracted               = Project.extract(state)
    val flags                   = parseFlags(args, extracted)
    val useGlobalVersion        = extracted.get(releaseIOMonorepoUseGlobalVersion)
    val selectedNames           = args.collect { case SelectProject(name) => name }
    val releaseVersionPairs     = args.collect { case ReleaseVersion(p, v) => p -> v }
    val nextVersionPairs        = args.collect { case NextVersion(p, v) => p -> v }
    val releaseVersionOverrides = releaseVersionPairs.toMap
    val nextVersionOverrides    = nextVersionPairs.toMap
    val globalReleaseVersions   = args.collect { case GlobalReleaseVersion(v) => v }
    val globalNextVersions      = args.collect { case GlobalNextVersion(v) => v }
    val globalReleaseVersion    = globalReleaseVersions.headOption
    val globalNextVersion       = globalNextVersions.headOption

    for {
      // ── Input format validation ──
      _ <- failWhen(
             releaseVersionOverrides.exists { case (p, v) => p.isEmpty || v.isEmpty },
             "Invalid release-version format. Expected project=version"
           )
      _ <- failWhen(
             nextVersionOverrides.exists { case (p, v) => p.isEmpty || v.isEmpty },
             "Invalid next-version format. Expected project=version"
           )
      _ <- failWhen(
             globalReleaseVersions.exists(_.isEmpty),
             "Invalid release-version format. Expected a non-empty version string"
           )
      _ <- failWhen(
             globalNextVersions.exists(_.isEmpty),
             "Invalid next-version format. Expected a non-empty version string"
           )
      // ── Per-project override duplicate detection ──
      _ <- failWhen(
             releaseVersionPairs.groupBy(_._1).exists(_._2.length > 1),
             "Duplicate per-project release-version overrides: " +
               releaseVersionPairs.groupBy(_._1).filter(_._2.length > 1).keys.mkString(", ")
           )
      _ <- failWhen(
             nextVersionPairs.groupBy(_._1).exists(_._2.length > 1),
             "Duplicate per-project next-version overrides: " +
               nextVersionPairs.groupBy(_._1).filter(_._2.length > 1).keys.mkString(", ")
           )
      // ── Override multiplicity & conflict ──
      _ <- failWhen(
             globalReleaseVersions.length > 1,
             "Multiple global release-version overrides provided. Only one is allowed"
           )
      _ <- failWhen(
             globalNextVersions.length > 1,
             "Multiple global next-version overrides provided. Only one is allowed"
           )
      _ <- failWhen(
             !useGlobalVersion &&
               (globalReleaseVersion.nonEmpty || globalNextVersion.nonEmpty),
             "Global version overrides (release-version <version>) are only supported " +
               "when releaseIOMonorepoUseGlobalVersion is enabled. " +
               "Use per-project overrides (release-version <project>=<version>) instead."
           )
      // Requires malformed-entry validations above to have short-circuited first.
      _ <- failWhen(
             (globalReleaseVersion.nonEmpty || globalNextVersion.nonEmpty) &&
               (releaseVersionOverrides.nonEmpty || nextVersionOverrides.nonEmpty),
             "Cannot mix global version overrides with per-project version overrides"
           )

      // ── Project resolution ──
      allProjects <-
        resolveProjects(
          state,
          extracted,
          releaseVersionOverrides,
          nextVersionOverrides,
          globalReleaseVersion,
          globalNextVersion,
          useGlobalVersion
        ).left.map(e => failWith(Option(e.getMessage).getOrElse(e.toString)))

      validNames       = allProjects.map(_.name).toSet
      invalidOverrides =
        (releaseVersionOverrides.keySet ++ nextVersionOverrides.keySet) -- validNames

      _ <- failWhen(
             invalidOverrides.nonEmpty,
             s"Unknown projects in version overrides: " +
               s"${invalidOverrides.mkString(", ")}. Available: ${validNames.mkString(", ")}"
           )

      // ── Project name validation ──
      invalid = selectedNames.filterNot(validNames.contains)
      _      <- failWhen(
                  selectedNames.nonEmpty && invalid.nonEmpty,
                  s"Unknown projects: ${invalid.mkString(", ")}. " +
                    s"Available: ${validNames.mkString(", ")}"
                )

      // ── Global version mode constraints ──
      _ <- failWhen(
             useGlobalVersion && selectedNames.nonEmpty &&
               selectedNames.toSet != validNames,
             s"Global version mode is active — all projects share a single " +
               s"version file. Selecting a subset of projects (${selectedNames.mkString(", ")}) is " +
               s"not supported. Release all projects or disable releaseIOMonorepoUseGlobalVersion."
           )
      _ <- failWhen(
             useGlobalVersion &&
               (releaseVersionOverrides.nonEmpty || nextVersionOverrides.nonEmpty),
             "Global version mode is active — all projects share a single version. " +
               "Per-project version overrides (release-version project=version) are not supported. " +
               "Use global overrides (release-version <version>) or remove per-project overrides."
           )

      // ── Selection & override consistency ──
      _ <- failWhen(
             flags.allChanged && selectedNames.nonEmpty,
             "Cannot combine 'all-changed' with explicit project selection. " +
               s"Either use 'all-changed' alone or specify projects explicitly."
           )

      selectedProjects =
        if (selectedNames.nonEmpty) allProjects.filter(p => selectedNames.contains(p.name))
        else allProjects

      // Only relevant when explicit project selection is active (selectedNames.nonEmpty).
      unusedOverrides =
        if (selectedNames.nonEmpty)
          (releaseVersionOverrides.keySet ++ nextVersionOverrides.keySet) --
            selectedProjects.map(_.name).toSet
        else Set.empty[String]
      _              <- failWhen(
                          unusedOverrides.nonEmpty,
                          s"Version overrides target non-selected projects: " +
                            s"${unusedOverrides.mkString(", ")}. " +
                            s"Selected: ${selectedProjects.map(_.name).mkString(", ")}"
                        )

      _ <- failWhen(
             selectedProjects.isEmpty,
             s"No projects selected for monorepo release. " +
               s"Available: ${validNames.mkString(", ")}"
           )
    } yield (selectedProjects, flags, selectedNames)
  }

  // ── Context building ──────────────────────────────────────────────

  private def buildContext(
      state: State,
      projects: Seq[ProjectReleaseInfo],
      flags: ReleaseFlags,
      selectedNames: Seq[String]
  ): MonorepoContext =
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
      case Left(failedState)                               => failedState
      case Right((selectedProjects, flags, selectedNames)) =>
        try {
          // Store parsed flags in State so steps (e.g. MonorepoVersionSteps) can read them.
          val decoratedState = state
            .put(ReleaseKeys.useDefaults, flags.useDefaults)
            .put(ReleaseKeys.skipTests, flags.skipTests)
            .put(ReleaseKeys.cross, flags.crossBuild)

          // Guards both monorepoReleaseProcess (can throw on misconfiguration) and
          // unsafeRunSync() (propagates IO failures as exceptions).
          val stepFns    = monorepoReleaseProcess(decoratedState)
          val initialCtx = buildContext(decoratedState, selectedProjects, flags, selectedNames)

          logReleaseStart(decoratedState, stepFns.length, selectedProjects.length, flags)

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
