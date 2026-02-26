package io.release.monorepo.examples

import cats.effect.{IO, Resource}
import io.release.monorepo.{
  MonorepoContext,
  MonorepoReleasePluginLike,
  MonorepoStepIO,
  ProjectReleaseInfo
}
import io.release.monorepo.steps.MonorepoReleaseSteps
import io.release.monorepo.MonorepoReleaseIO.*
import sbt.*
import sbt.Keys.*

/**
 * Examples showing how to create custom monorepo release steps and compose them
 * with the built-in steps.
 *
 * Monorepo steps come in two flavors:
 *   - '''Global''' — runs once for the entire release (e.g., VCS checks, push)
 *   - '''PerProject''' — runs once per selected project in topological order
 */
object CustomMonorepoStepExamples {

  /** How to read this file (recommended path):
    *  1. Start with `minimalProcess` for an immediate working setup.
    *  2. Move to `firstCustomProcess` for the smallest meaningful customization.
    *  3. Use `MyMonorepoRelease` for advanced resource-aware customization.
    *
    * Note: plugin objects like `MyMonorepoRelease` must live in `project/MyMonorepoRelease.scala`
    * to be discovered by sbt. The objects below are examples to copy there.
    */

  /** Minimal working setup.
    *
    * Usage in build.sbt (root project):
    * {{{
    * lazy val root = (project in file("."))
    *   .aggregate(core, api)
    *   .enablePlugins(MonorepoReleasePlugin)
    *
    * import io.release.monorepo.MonorepoReleasePlugin.autoImport._
    * import io.release.monorepo.examples.CustomMonorepoStepExamples
    * releaseIOMonorepoProcess := CustomMonorepoStepExamples.minimalProcess
    * }}}
    *
    * Run with: `sbt "releaseIOMonorepo with-defaults"`
    *
    * Recommended command modes:
    *   - changed projects: `sbt "releaseIOMonorepo with-defaults"`
    *   - explicit projects: `sbt "releaseIOMonorepo core api with-defaults"`
    *   - all projects: `sbt "releaseIOMonorepo all-changed with-defaults"`
    *
    * Avoid mixing explicit project names with `all-changed` in one command.
    */
  val minimalProcess: Seq[MonorepoStepIO] = MonorepoReleaseSteps.defaults

  /** First customization: prepend one custom step and keep the default flow.
    *
    * Usage in build.sbt (root project):
    * {{{
    * import io.release.monorepo.MonorepoReleasePlugin.autoImport._
    * import io.release.monorepo.examples.CustomMonorepoStepExamples
    * releaseIOMonorepoProcess := CustomMonorepoStepExamples.firstCustomProcess
    * }}}
    *
    * Run with: `sbt "releaseIOMonorepo with-defaults"`
    */
  lazy val firstCustomProcess: Seq[MonorepoStepIO] =
    Seq(printSummary) ++ MonorepoReleaseSteps.defaults

  // --- Global step: print a release summary ---

  val printSummary: MonorepoStepIO = globalStep("print-summary") { ctx =>
    val projectList = ctx.currentProjects.map(_.name).mkString(", ")
    IO.println(s"[monorepo] Releasing projects: $projectList") *>
      IO.pure(ctx)
  }

  // --- Global step: validate branch name ---

  val validateBranch: MonorepoStepIO = MonorepoStepIO.Global(
    name = "validate-branch",
    action = { ctx =>
      ctx.vcs match {
        case Some(vcs) =>
          for {
            branch <- IO.blocking(vcs.currentBranch)
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
      IO.blocking {
        val readme = new java.io.File(project.baseDir, "README.md")
        if (!readme.exists())
          throw new RuntimeException(
            s"Project '${project.name}' is missing README.md at ${project.baseDir}"
          )
        ctx
      }
    }

  // --- Per-project step: generate a changelog per project ---
  // Intentionally simple demo logic: append-only and not fully idempotent.

  val generateChangelog: MonorepoStepIO =
    perProjectStep("generate-changelog") { (ctx, project) =>
      project.versions match {
        case Some((releaseVer, _)) =>
          IO.blocking {
            val file     = new java.io.File(project.baseDir, "CHANGELOG.md")
            val entry    = s"\n## $releaseVer\n\n- Release $releaseVer\n"
            val existing =
              if (file.exists())
                scala.util.Using(scala.io.Source.fromFile(file))(_.mkString).get
              else s"# ${project.name} Changelog\n"
            java.nio.file.Files.write(file.toPath, (existing + entry).getBytes("UTF-8"))
          } *>
            IO.println(
              s"[monorepo] Updated CHANGELOG.md for ${project.name} $releaseVer"
            ) *>
            IO.pure(ctx)
        case None                  =>
          IO.println(
            s"[monorepo] Skipping changelog for ${project.name} — no versions set"
          ).as(ctx)
      }
    }

  // --- Global step: store a custom attribute ---

  val markReleaseDone: MonorepoStepIO = globalStep("mark-done") { ctx =>
    IO.pure(ctx.withAttr("release-completed", "true"))
  }

  // --- Conditional per-project step: skip failed projects ---

  def conditionalPerProject(
      name: String,
      condition: ProjectReleaseInfo => Boolean,
      step: (MonorepoContext, ProjectReleaseInfo) => IO[MonorepoContext]
  ): MonorepoStepIO =
    perProjectStep(s"conditional-$name") { (ctx, project) =>
      if (condition(project)) step(ctx, project)
      else
        IO.println(
          s"[monorepo] Skipping $name for ${project.name} (condition not met)"
        ).as(ctx)
    }

  // --- Composing a custom release process ---

  /**
   * Example: a custom monorepo release process that adds a summary banner,
   * validates the branch, generates per-project changelogs, and skips push.
   *
   * Usage in build.sbt:
   * {{{
   * import io.release.monorepo.MonorepoReleasePlugin.autoImport._
   * import io.release.monorepo.examples.CustomMonorepoStepExamples
   *
   * releaseIOMonorepoProcess := CustomMonorepoStepExamples.customProcess
   * }}}
   *
   * Run with: `sbt "releaseIOMonorepo with-defaults"`
   */
  val customProcess: Seq[MonorepoStepIO] = Seq(
    MonorepoReleaseSteps.initializeVcs,
    printSummary,
    validateBranch,
    MonorepoReleaseSteps.checkCleanWorkingDir,
    MonorepoReleaseSteps.resolveReleaseOrder,
    MonorepoReleaseSteps.detectOrSelectProjects,
    MonorepoReleaseSteps.checkSnapshotDependencies,
    MonorepoReleaseSteps.inquireVersions,
    MonorepoReleaseSteps.validateVersionConsistency,
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

// --- Resource-aware custom plugin example ---

/** Placeholder for an HTTP client used in the resource-aware plugin example below. */
trait HttpClient {
  def get(path: String): String
  def post(path: String, body: String): Unit
  def close(): Unit
}

/**
 * Example: a custom monorepo release plugin that acquires an HTTP client
 * once and uses it in resource-aware steps at non-adjacent positions.
 *
 * Copy this object to `project/MyMonorepoRelease.scala` in your build so sbt can discover it.
 * In that file, prefer `_root_` imports (for example `_root_.io.release...`) because
 * `import sbt.*` can shadow the `io` package.
 * If you keep a package declaration in that file, import the plugin symbol in `build.sbt`
 * before calling `enablePlugins(...)`.
 *
 * Enable on the root project in build.sbt:
 * {{{
 * // if MyMonorepoRelease is in the default package in project/MyMonorepoRelease.scala:
 * lazy val root = (project in file("."))
 *   .aggregate(core, api, web)
 *   .enablePlugins(MyMonorepoRelease)
 *
 * // if MyMonorepoRelease has a package:
 * // import your.package.MyMonorepoRelease
 * // lazy val root = (project in file(".")).enablePlugins(MyMonorepoRelease)
 * }}}
 *
 * Run with:
 * {{{
 * sbt "releaseMonorepoCustom core api with-defaults"
 * }}}
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
          val invalid = ctx.currentProjects.map(_.name).filterNot(allowed.contains)
          if (invalid.nonEmpty)
            throw new RuntimeException(s"Projects not allowed: ${invalid.mkString(", ")}")
          ctx
        }
      },
      MonorepoReleaseSteps.checkSnapshotDependencies,
      MonorepoReleaseSteps.inquireVersions,
      MonorepoReleaseSteps.validateVersionConsistency,
      MonorepoReleaseSteps.runClean,
      MonorepoReleaseSteps.runTests,
      MonorepoReleaseSteps.setReleaseVersions,
      MonorepoReleaseSteps.commitReleaseVersions,
      MonorepoReleaseSteps.tagReleases,
      // 2nd resource step — notify Slack after tagging
      resourceGlobalStep[HttpClient]("notify-slack") { client => ctx =>
        IO.blocking {
          val summary = ctx.currentProjects
            .flatMap(p => p.versions.map { case (v, _) => s"${p.name} $v" })
            .mkString(", ")
          client.post("/slack-webhook", s"""{"text": "Tagged: ${summary}"}""")
          ctx
        }
      },
      MonorepoReleaseSteps.publishArtifacts,
      // 3rd resource step — verify published artifacts per project
      resourcePerProjectStep[HttpClient]("verify-publish") { client => (ctx, project) =>
        project.versions match {
          case Some((releaseVer, _)) =>
            IO.blocking {
              client.get(s"/artifacts/${project.name}/$releaseVer")
              ctx
            }
          case None                  => IO.pure(ctx)
        }
      },
      MonorepoReleaseSteps.setNextVersions,
      MonorepoReleaseSteps.commitNextVersions,
      MonorepoReleaseSteps.pushChanges
    )
}

// --- Dynamic project discovery example ---

/**
 * Example: a monorepo release plugin that dynamically discovers subprojects
 * by scanning the `modules/` directory for subdirectories containing a `version.sbt` file.
 *
 * Copy this object to `project/DynamicMonorepoPlugin.scala` in your build so sbt can discover it.
 * In that file, prefer `_root_` imports (for example `_root_.io.release...`) because
 * `import sbt.*` can shadow the `io` package.
 *
 * Usage in build.sbt:
 * {{{
 * lazy val root = (project in file("."))
 *   .enablePlugins(DynamicMonorepoPlugin)
 *   .aggregate(DynamicMonorepoPlugin.extraProjects.map(p => LocalProject(p.id)): _*)
 * }}}
 *
 * Run with:
 * {{{
 * sbt "releaseDynamic with-defaults"
 * }}}
 */
object DynamicMonorepoPlugin extends MonorepoReleasePluginLike[Unit] {

  // Example value: align this with your build's Scala version.
  private val ScalaVersion = "2.13.16"

  override def trigger               = noTrigger
  override protected def commandName = "releaseDynamic"

  override def requires: Plugins = _root_.io.release.ReleasePluginIO

  override def resource: Resource[IO, Unit] = Resource.unit

  override def extraProjects: Seq[Project] = {
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
