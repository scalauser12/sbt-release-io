package io.release.monorepo.examples

import cats.effect.IO
import cats.effect.Resource
import io.release.monorepo.MonorepoGlobalHookIO
import io.release.monorepo.MonorepoGlobalResourceHookIO
import io.release.monorepo.MonorepoProjectHookIO
import io.release.monorepo.MonorepoProjectResourceHookIO
import io.release.monorepo.MonorepoReleaseIO
import io.release.monorepo.MonorepoReleasePluginLike
import io.release.monorepo.MonorepoResourceHooks
import io.release.monorepo.MonorepoStepIO
import sbt.*

/** Examples showing the supported hook/policy monorepo customization path.
  *
  * Monorepo steps come in two flavors:
  *   - '''Global''' — runs once for the entire release (e.g., VCS checks, push)
  *   - '''PerProject''' — runs once per selected project in topological order
  *
  * '''How to read this file (recommended path):'''
  *   1. Start with `firstHookSettings` for an immediate hook-based setup.
  *   2. Move to `customHookSettings` for a richer global/per-project example.
  *   3. See `MyMonorepoRelease` for advanced resource-aware customization.
  *
  * Plugin objects like `MyMonorepoRelease` must live in `project/` (as `.scala` files)
  * to be discovered by sbt. The objects below are examples to copy there.
 */
object CustomMonorepoStepExamples {

  private val releaseCompletedKey = AttributeKey[Boolean]("releaseCompleted")

  /** First customization: keep the compiled defaults, disable push, and add one global hook.
    *
    * {{{
    * lazy val root = (project in file("."))
    *   .settings(CustomMonorepoStepExamples.firstHookSettings)
    * }}}
    *
    * Run with: `sbt "releaseIOMonorepo with-defaults"`
    */
  val firstHookSettings: Seq[Setting[?]] = Seq(
    MonorepoReleaseIO.releaseIOMonorepoPolicyEnablePush := false,
    MonorepoReleaseIO.releaseIOMonorepoHooksAfterSelection += printSummaryHook
  )

  /** A richer hook-based setup with policy toggles and a mix of global and per-project hooks.
    *
    * {{{
    * lazy val root = (project in file("."))
    *   .settings(CustomMonorepoStepExamples.customHookSettings)
    * }}}
    */
  val customHookSettings: Seq[Setting[?]] = Seq(
    MonorepoReleaseIO.releaseIOMonorepoPolicyEnablePush := false,
    MonorepoReleaseIO.releaseIOMonorepoHooksAfterSelection += printSummaryHook,
    MonorepoReleaseIO.releaseIOMonorepoHooksBeforeVersionResolution += checkReadmeHook,
    MonorepoReleaseIO.releaseIOMonorepoHooksAfterVersionResolution += generateChangelogHook,
    MonorepoReleaseIO.releaseIOMonorepoHooksAfterNextCommit += markReleaseDoneHook
  )

  /** Recommended template for selective monorepo rehearsals driven by change detection.
    *
    * This keeps the compiled built-ins, disables mutating remote phases, skips `run-clean`,
    * includes downstream dependents of changed projects, and logs the resolved selection after
    * `detect-or-select-projects`.
    *
    * {{{
    * lazy val root = (project in file("."))
    *   .aggregate(core, api, web)
    *   .settings(CustomMonorepoStepExamples.selectionAndDetectionSettings)
    *
    * // Detect changes automatically and include downstream dependents
    * // sbt "releaseIOMonorepo check with-defaults"
    *
    * // Rehearse a focused release for one project with explicit versions
    * // sbt "releaseIOMonorepo check api with-defaults release-version api=1.1.0 next-version api=1.2.0-SNAPSHOT"
    * }}}
    */
  val selectionAndDetectionSettings: Seq[Setting[?]] = Seq(
    MonorepoReleaseIO.releaseIOMonorepoPolicyEnablePush           := false,
    MonorepoReleaseIO.releaseIOMonorepoPolicyEnablePublish        := false,
    MonorepoReleaseIO.releaseIOMonorepoPolicyEnableRunClean       := false,
    MonorepoReleaseIO.releaseIOMonorepoDetectionIncludeDownstream := true,
    MonorepoReleaseIO.releaseIOMonorepoHooksAfterSelection += printSummaryHook
  )

  /** Recommended template for targeted project rehearsals with explicit selectors.
    *
    * This keeps the built-in process, disables remote phases, and adds lightweight validation
    * around selection and version resolution so you can focus on one project at a time.
    *
    * {{{
    * lazy val root = (project in file("."))
    *   .aggregate(core, api, web)
    *   .settings(CustomMonorepoStepExamples.targetedRehearsalSettings)
    *
    * // Rehearse a targeted release plan
    * // sbt "releaseIOMonorepo check api with-defaults release-version api=1.1.0 next-version api=1.2.0-SNAPSHOT"
    * }}}
    */
  val targetedRehearsalSettings: Seq[Setting[?]] = Seq(
    MonorepoReleaseIO.releaseIOMonorepoPolicyEnablePush     := false,
    MonorepoReleaseIO.releaseIOMonorepoPolicyEnablePublish  := false,
    MonorepoReleaseIO.releaseIOMonorepoPolicyEnableRunClean := false,
    MonorepoReleaseIO.releaseIOMonorepoHooksAfterSelection += printSummaryHook,
    MonorepoReleaseIO.releaseIOMonorepoHooksBeforeVersionResolution += checkReadmeHook
  )

  val printSummaryHook: MonorepoGlobalHookIO = MonorepoGlobalHookIO.action("print-summary")(ctx =>
    IO.println(s"[monorepo] Releasing projects: ${ctx.currentProjects.map(_.name).mkString(", ")}")
  )

  val checkReadmeHook: MonorepoProjectHookIO = MonorepoProjectHookIO.action("check-readme") {
    (_, project) =>
      if (!(new java.io.File(project.baseDir, "README.md")).exists())
        IO.raiseError(
          new RuntimeException(
            s"Project '${project.name}' is missing README.md at ${project.baseDir}"
          )
        )
      else IO.unit
  }

  val generateChangelogHook: MonorepoProjectHookIO =
    MonorepoProjectHookIO.action("generate-changelog") { (_, project) =>
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

  val markReleaseDoneHook: MonorepoGlobalHookIO =
    MonorepoGlobalHookIO.io("mark-done")(ctx =>
      IO.pure(ctx.withMetadata(releaseCompletedKey, true))
    )

  // --- Global step: print a release summary ---

  val printSummary: MonorepoStepIO = MonorepoStepIO
    .global("print-summary")
    .executeAction(ctx =>
      IO.println(
        s"[monorepo] Releasing projects: ${ctx.currentProjects.map(_.name).mkString(", ")}"
      )
    )

  // --- Global step: validate branch name ---

  val validateBranch: MonorepoStepIO = MonorepoStepIO
    .global("validate-branch")
    .execute(ctx =>
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
    )

  // --- Per-project step: check for a required file ---

  val checkReadmeExists: MonorepoStepIO = MonorepoStepIO
    .perProject("check-readme")
    .execute((ctx, project) =>
      if (!(new java.io.File(project.baseDir, "README.md")).exists())
        IO.raiseError(
          new RuntimeException(
            s"Project '${project.name}' is missing README.md at ${project.baseDir}"
          )
        )
      else IO.pure(ctx)
    )

  // --- Per-project step: generate a changelog per project ---
  // Intentionally simple demo logic: append-only and not fully idempotent.

  val generateChangelog: MonorepoStepIO = MonorepoStepIO
    .perProject("generate-changelog")
    .executeAction((ctx, project) =>
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
    )

  // --- Global step: store a custom attribute ---

  val markReleaseDone: MonorepoStepIO = MonorepoStepIO
    .global("mark-done")
    .execute(ctx => IO.pure(ctx.withMetadata(releaseCompletedKey, true)))

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

/** Advanced/custom-plugin example: acquire an HTTP client once and use it in
  * resource-aware hooks while staying on compiled hook mode.
  *
  * Prefer hook/policy settings for routine customization. Copy to
  * `project/MyMonorepoRelease.scala` so sbt can discover it. Use `_root_` imports in that
  * file (e.g. `_root_.io.release...`) because `import sbt.*` shadows the `io` package.
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

  private val validateProjects: MonorepoGlobalResourceHookIO[HttpClient] =
    MonorepoGlobalResourceHookIO.io[HttpClient]("validate-projects")(client =>
      ctx =>
        IO.blocking {
          val allowed = client.get("/allowed-projects").split(",").toSet
          ctx.currentProjects.map(_.name).filterNot(allowed.contains)
        }.flatMap(invalid =>
          if (invalid.nonEmpty)
            IO.raiseError(
              new RuntimeException(s"Projects not allowed: ${invalid.mkString(", ")}")
            )
          else IO.pure(ctx)
        )
    )

  private val notifySlack: MonorepoGlobalResourceHookIO[HttpClient] =
    MonorepoGlobalResourceHookIO.action[HttpClient]("notify-slack")(client =>
      ctx =>
        IO.blocking {
          val summary = ctx.currentProjects
            .flatMap(p => p.versions.map { case (v, _) => s"${p.name} $v" })
            .mkString(", ")
          client.post("/slack-webhook", s"""{"text": "Tagged: ${summary}"}""")
        }
    )

  private val verifyPublish: MonorepoProjectResourceHookIO[HttpClient] =
    MonorepoProjectResourceHookIO.action[HttpClient]("verify-publish")(client =>
      (_, project) =>
        project.versions match {
          case Some((releaseVer, _)) =>
            IO.blocking(client.get(s"/artifacts/${project.name}/$releaseVer")).void
          case None                  => IO.unit
        }
    )

  override protected def monorepoResourceHooks(
      state: State
  ): MonorepoResourceHooks[HttpClient] =
    MonorepoResourceHooks(
      afterSelectionHooks = Seq(validateProjects),
      afterReleaseCommitHooks = Seq(notifySlack),
      afterPublishHooks = Seq(verifyPublish)
    )
}
