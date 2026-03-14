package io.release.monorepo.examples

import cats.effect.{IO, Resource}
import io.release.ReleasePluginIO
import io.release.monorepo.{MonorepoReleasePluginLike, MonorepoStepIO}
import io.release.monorepo.steps.MonorepoReleaseSteps
import io.release.monorepo.MonorepoReleaseIO.*
import sbt.*
import sbt.Keys.*

/** Examples showing how to create custom monorepo release steps and compose them
  * with the built-in steps.
  *
  * Monorepo steps come in two flavors:
  *   - '''Global''' — runs once for the entire release (e.g., VCS checks, push)
  *   - '''PerProject''' — runs once per selected project in topological order
  *
  * Recommended reading path:
  *   1. Start with `minimalProcess` for an immediate working setup.
  *   2. Move to `firstCustomProcess` for the smallest meaningful customization.
  *   3. See `MyMonorepoRelease` for advanced resource-aware customization.
  *
  * Plugin objects like `MyMonorepoRelease` must live in `project/` (as `.scala` files)
  * to be discovered by sbt. The objects below are examples to copy there.
  */
object CustomMonorepoStepExamples {

  private val releaseCompletedKey = AttributeKey[Boolean]("releaseCompleted")

  /** Minimal working setup — just the default steps.
    *
    * {{{ releaseIOMonorepoProcess := CustomMonorepoStepExamples.minimalProcess }}}
    *
    * Run with: `sbt "releaseIOMonorepo with-defaults"`
    */
  val minimalProcess: Seq[MonorepoStepIO] = MonorepoReleaseSteps.defaults

  /** Insert a custom step after project selection, keeping the default flow.
    *
    * {{{ releaseIOMonorepoProcess := CustomMonorepoStepExamples.firstCustomProcess }}}
    *
    * Run with: `sbt "releaseIOMonorepo with-defaults"`
    */
  lazy val firstCustomProcess: Seq[MonorepoStepIO] =
    insertStepAfter(MonorepoReleaseSteps.defaults, "detect-or-select-projects")(
      Seq(printSummary)
    )

  // --- Global step: print a release summary ---

  val printSummary: MonorepoStepIO = globalStepAction("print-summary") { ctx =>
    val projectList = ctx.currentProjects.map(_.name).mkString(", ")
    IO.println(s"[monorepo] Releasing projects: $projectList")
  }

  // --- Global step: validate branch name ---

  val validateBranch: MonorepoStepIO = MonorepoStepIO.Global(
    name = "validate-branch",
    execute = { ctx =>
      ctx.vcs match {
        case Some(vcs) =>
          for {
            branch <- vcs.currentBranch
            result <- if (branch == "main" || branch == "master") IO.pure(ctx)
                      else
                        IO.raiseError(
                          new RuntimeException(
                            s"Releases must be done from main/master, not '$branch'"
                          )
                        )
          } yield result
        case None      =>
          IO.raiseError(new RuntimeException("VCS not initialized"))
      }
    }
  )

  // --- Per-project step: check for a required file ---

  val checkReadmeExists: MonorepoStepIO =
    perProjectStep("check-readme") { (ctx, project) =>
      val readme = new java.io.File(project.baseDir, "README.md")
      if (!readme.exists())
        IO.raiseError(
          new RuntimeException(
            s"Project '${project.name}' is missing README.md at ${project.baseDir}"
          )
        )
      else IO.pure(ctx)
    }

  // --- Per-project step: generate a changelog per project ---
  // Intentionally simple demo logic: append-only and not fully idempotent.

  val generateChangelog: MonorepoStepIO =
    perProjectStepAction("generate-changelog") { (ctx, project) =>
      project.versions match {
        case Some((releaseVer, _)) =>
          IO.blocking {
            val file     = project.baseDir / "CHANGELOG.md"
            val entry    = s"\n## $releaseVer\n\n- Release $releaseVer\n"
            val existing =
              if (file.exists()) sbt.IO.read(file) else s"# ${project.name} Changelog\n"
            sbt.IO.write(file, existing + entry)
          } *> IO.println(s"[monorepo] Updated CHANGELOG.md for ${project.name} $releaseVer")
        case None                  =>
          IO.println(s"[monorepo] Skipping changelog for ${project.name} — no versions set")
      }
    }

  // --- Global step: store a custom attribute ---

  val markReleaseDone: MonorepoStepIO = globalStep("mark-done") { ctx =>
    IO.pure(ctx.withMetadata(releaseCompletedKey, true))
  }

  // --- Composing a custom release process ---

  /** A custom release process: summary banner, branch validation, changelogs, no push.
    *
    * {{{ releaseIOMonorepoProcess := CustomMonorepoStepExamples.customProcess }}}
    *
    * Run with: `sbt "releaseIOMonorepo with-defaults"`
    */
  val customProcess: Seq[MonorepoStepIO] = Seq(
    MonorepoReleaseSteps.initializeVcs,
    validateBranch,
    MonorepoReleaseSteps.checkCleanWorkingDir,
    MonorepoReleaseSteps.resolveReleaseOrder,
    MonorepoReleaseSteps.detectOrSelectProjects,
    printSummary,
    MonorepoReleaseSteps.checkSnapshotDependencies,
    MonorepoReleaseSteps.inquireVersions,
    MonorepoReleaseSteps.validateVersions,
    checkReadmeExists,
    generateChangelog,
    MonorepoReleaseSteps.runClean,
    MonorepoReleaseSteps.runTests,
    MonorepoReleaseSteps.setReleaseVersions,
    MonorepoReleaseSteps.commitReleaseVersions,
    MonorepoReleaseSteps.tagReleases,
    MonorepoReleaseSteps.publishArtifacts,
    MonorepoReleaseSteps.setNextVersions,
    MonorepoReleaseSteps.commitNextVersions,
    markReleaseDone
    // Note: pushChanges intentionally omitted — push manually after verifying
  )
}

// ══════════════════════════════════════════════════════════════════════
// Resource-aware plugin example (copy to project/*.scala)
// ══════════════════════════════════════════════════════════════════════

/** Placeholder for an HTTP client used in the resource-aware plugin example below. */
trait HttpClient {
  def get(path: String): String
  def post(path: String, body: String): Unit
  def close(): Unit
}

/** A custom monorepo release plugin that acquires an HTTP client once and
  * uses it in resource-aware steps at non-adjacent positions.
  *
  * Copy to `project/MyMonorepoRelease.scala` so sbt can discover it. Use `_root_` imports
  * in that file (e.g. `_root_.io.release...`) because `import sbt.*` shadows the `io` package.
  * If the plugin has a package, import it in `build.sbt` before `enablePlugins(...)`.
  *
  * {{{
  * lazy val root = (project in file("."))
  *   .aggregate(core, api, web)
  *   .enablePlugins(MyMonorepoRelease)
  * }}}
  *
  * Run with: `sbt "releaseMonorepoCustom core api with-defaults"`
  */
object MyMonorepoRelease extends MonorepoReleasePluginLike[HttpClient] {

  override def trigger               = noTrigger
  override protected def commandName = "releaseMonorepoCustom"

  override def resource: Resource[IO, HttpClient] =
    Resource.make(
      IO {
        new HttpClient {
          def get(path: String): String              = s"GET $path"
          def post(path: String, body: String): Unit = ()
          def close(): Unit                          = ()
        }
      }
    )(c => IO(c.close()))

  override protected def monorepoReleaseProcess(
      state: State
  ): Seq[HttpClient => MonorepoStepIO] =
    Seq(
      // Plain steps — lifted automatically via the implicit conversion
      MonorepoReleaseSteps.initializeVcs,
      MonorepoReleaseSteps.checkCleanWorkingDir,
      MonorepoReleaseSteps.resolveReleaseOrder,
      MonorepoReleaseSteps.detectOrSelectProjects,
      // 1st resource step — validate allowed projects via API
      resourceGlobalStep[HttpClient]("validate-projects") { client => ctx =>
        IO.blocking {
          val allowed = client.get("/allowed-projects").split(",").toSet
          ctx.currentProjects.map(_.name).filterNot(allowed.contains)
        }.flatMap { invalid =>
          if (invalid.nonEmpty)
            IO.raiseError(new RuntimeException(s"Projects not allowed: ${invalid.mkString(", ")}"))
          else IO.pure(ctx)
        }
      },
      MonorepoReleaseSteps.checkSnapshotDependencies,
      MonorepoReleaseSteps.inquireVersions,
      MonorepoReleaseSteps.validateVersions,
      MonorepoReleaseSteps.runClean,
      MonorepoReleaseSteps.runTests,
      MonorepoReleaseSteps.setReleaseVersions,
      MonorepoReleaseSteps.commitReleaseVersions,
      MonorepoReleaseSteps.tagReleases,
      // 2nd resource step — notify Slack after tagging
      resourceGlobalStepAction[HttpClient]("notify-slack") { client => ctx =>
        IO.blocking {
          val summary = ctx.currentProjects
            .flatMap(p => p.versions.map { case (v, _) => s"${p.name} $v" })
            .mkString(", ")
          client.post("/slack-webhook", s"""{"text": "Tagged: ${summary}"}""")
        }
      },
      MonorepoReleaseSteps.publishArtifacts,
      // 3rd resource step — verify published artifacts per project
      resourcePerProjectStepAction[HttpClient]("verify-publish") { client => (ctx, project) =>
        project.versions match {
          case Some((releaseVer, _)) =>
            IO.blocking {
              client.get(s"/artifacts/${project.name}/$releaseVer")
              ()
            }
          case None                  => IO.unit
        }
      },
      MonorepoReleaseSteps.setNextVersions,
      MonorepoReleaseSteps.commitNextVersions,
      MonorepoReleaseSteps.pushChanges
    )
}

// ══════════════════════════════════════════════════════════════════════
// Dynamic project discovery example (copy to project/*.scala)
// ══════════════════════════════════════════════════════════════════════

/** A monorepo release plugin that dynamically discovers subprojects by scanning
  * the `modules/` directory for subdirectories containing a `version.sbt` file.
  *
  * Copy to `project/DynamicMonorepoPlugin.scala` so sbt can discover it. Use `_root_` imports
  * in that file (e.g. `_root_.io.release...`) because `import sbt.*` shadows the `io` package.
  *
  * {{{
  * lazy val root = (project in file("."))
  *   .enablePlugins(DynamicMonorepoPlugin)
  *   .aggregate(DynamicMonorepoPlugin.extraProjects.map(p => LocalProject(p.id) ): _* )
  * }}}
  *
  * Run with: `sbt "releaseDynamic with-defaults"`
  */
object DynamicMonorepoPlugin extends MonorepoReleasePluginLike[Unit] {

  // Example value: align this with your build's Scala version.
  private val ScalaVersion = "2.13.16"

  override def trigger               = noTrigger
  override protected def commandName = "releaseDynamic"

  override def requires: Plugins = ReleasePluginIO

  override def resource: Resource[IO, Unit] = Resource.unit

  override lazy val extraProjects: Seq[Project] = {
    val modulesDir = file("modules")
    val dirs       =
      if (modulesDir.exists && modulesDir.isDirectory)
        Option(modulesDir.listFiles)
          .getOrElse(Array.empty[File])
          .filter(_.isDirectory)
          .filter(dir => (dir / "version.sbt").exists)
          .sorted
          .toSeq
      else Seq.empty

    dirs.map { dir =>
      Project(dir.getName, dir)
        .settings(
          name         := dir.getName,
          scalaVersion := ScalaVersion
        )
    }
  }
}
