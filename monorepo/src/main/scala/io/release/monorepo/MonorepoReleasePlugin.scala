package io.release.monorepo

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

  // Parses all arguments as raw tokens, then interprets them sequentially.
  // This avoids ambiguity between the catch-all project name parser and
  // multi-token parsers like "release-version <project>=<version>" which
  // conflict under sbt's `|` combinator with `.*` repetition.
  protected lazy val monorepoParser: Parser[Seq[MonorepoArg]] =
    (Space ~> token(NotSpace)).* map interpretTokens

  protected def interpretTokens(tokens: Seq[String]): Seq[MonorepoArg] = {
    import MonorepoArg.*

    val result = Seq.newBuilder[MonorepoArg]
    val iter   = tokens.iterator.buffered

    while (iter.hasNext) {
      iter.next() match {
        case "with-defaults"   => result += WithDefaults
        case "skip-tests"      => result += SkipTests
        case "cross"           => result += CrossBuild
        case "all-changed"     => result += AllChanged
        case "release-version" =>
          if (!iter.hasNext)
            throw new RuntimeException("release-version requires <project>=<version> argument")
          val s     = iter.next()
          val parts = s.split("=", 2)
          if (parts.length != 2)
            throw new RuntimeException(
              s"Invalid release-version format: '$s'. Expected project=version"
            )
          result += ReleaseVersion(parts(0), parts(1))
        case "next-version"    =>
          if (!iter.hasNext)
            throw new RuntimeException("next-version requires <project>=<version> argument")
          val s     = iter.next()
          val parts = s.split("=", 2)
          if (parts.length != 2)
            throw new RuntimeException(
              s"Invalid next-version format: '$s'. Expected project=version"
            )
          result += NextVersion(parts(0), parts(1))
        case name              => result += SelectProject(name)
      }
    }
    result.result()
  }

  // ── Release execution ───────────────────────────────────────────────

  protected def doMonorepoRelease(state: State, args: Seq[MonorepoArg]): State = {
    import MonorepoArg.*

    val extracted = extract(state)

    // Parse flags
    val useDefaults = args.contains(WithDefaults)
    val skipTests   = args.contains(SkipTests) || extracted.get(releaseIOMonorepoSkipTests)
    val crossBuild  = args.contains(CrossBuild) || extracted.get(releaseIOMonorepoCrossBuild)
    val allChanged  = args.contains(AllChanged)
    val skipPublish = extracted.get(releaseIOMonorepoSkipPublish)
    val interactive = extracted.get(releaseIOMonorepoInteractive)
    val tagStrategy = extracted.get(releaseIOMonorepoTagStrategy)

    // Collect explicit project selections
    val selectedNames = args.collect { case SelectProject(name) => name }

    // Collect per-project version overrides
    val releaseVersionOverrides = args.collect { case ReleaseVersion(p, v) => p -> v }.toMap
    val nextVersionOverrides    = args.collect { case NextVersion(p, v) => p -> v }.toMap

    // Resolve all available monorepo projects
    val allProjectRefs = extracted.get(releaseIOMonorepoProjects)
    val versionFileFn  = extracted.get(releaseIOMonorepoVersionFile)

    val allProjects: Seq[ProjectReleaseInfo] = allProjectRefs.map { ref =>
      val projBase = (ref / baseDirectory).get(extracted.structure.data).getOrElse {
        extracted.get(baseDirectory)
      }
      val projName = ref.project
      ProjectReleaseInfo(
        ref = ref,
        name = projName,
        baseDir = projBase,
        versionFile = versionFileFn(ref),
        versions = for {
          rel  <- releaseVersionOverrides.get(projName)
          next <- nextVersionOverrides.get(projName).orElse(Some(""))
        } yield (rel, next)
      )
    }

    // Filter to selected projects (if explicit selection provided)
    val selectedProjects =
      if (selectedNames.nonEmpty) {
        val validNames = allProjects.map(_.name).toSet
        val invalid    = selectedNames.filterNot(validNames.contains)
        if (invalid.nonEmpty) {
          state.log.error(
            s"[release-io-monorepo] Unknown projects: ${invalid.mkString(", ")}. " +
              s"Available: ${validNames.mkString(", ")}"
          )
          return state.fail
        }
        allProjects.filter(p => selectedNames.contains(p.name))
      } else {
        allProjects
      }

    // Get step functions (T => MonorepoStepIO)
    val stepFns = monorepoReleaseProcess(state)

    // Build initial context
    val initialCtx = MonorepoContext(
      state = state,
      projects = selectedProjects,
      skipTests = skipTests,
      skipPublish = skipPublish,
      interactive = interactive && !useDefaults,
      tagStrategy = tagStrategy,
      attributes =
        if (selectedNames.nonEmpty || !allChanged) Map("projects-selected" -> "true")
        else Map.empty
    )

    state.log.info("[release-io-monorepo] Starting monorepo release...")
    state.log.info(
      s"[release-io-monorepo] ${stepFns.length} steps, ${selectedProjects.length} project(s)"
    )
    if (skipTests) state.log.info("[release-io-monorepo] Tests will be skipped")
    if (skipPublish) state.log.info("[release-io-monorepo] Publish will be skipped")

    // The resource is acquired once and shared across all steps; it is released after all
    // steps complete (or immediately on failure). Each step function receives the resource T.
    val program = resource.use { t =>
      val steps = stepFns.map(_(t))
      MonorepoStepIO.compose(steps)(initialCtx)
    }

    try {
      // unsafeRunSync() blocks the sbt command thread — unavoidable at the sbt plugin boundary.
      val finalCtx = program.unsafeRunSync()
      finalCtx.state.log.info("[release-io-monorepo] Monorepo release completed successfully!")
      finalCtx.state
    } catch {
      case e: RuntimeException =>
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
