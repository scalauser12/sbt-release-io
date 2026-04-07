package io.release.examples

import cats.effect.IO
import cats.effect.Resource
import io.release.ReleaseHookIO
import io.release.ReleasePluginIO.autoImport.*
import io.release.ReleasePluginIOLike
import io.release.ReleaseResourceHookIO
import io.release.ReleaseResourceHooks
import io.release.internal.ReleaseLogPrefixes
import sbt.*
import sbt.Keys.thisProject

import scala.util.control.NonFatal

/**
 * Examples showing the supported hook/policy customization path.
 *
 * '''How to read this file (recommended path):'''
 *  1. Start with `firstHookSettings` for a working hook-based setup.
 *  2. Move to `customHookSettings` for a richer policy + lifecycle example.
 *  3. Use `MyReleasePlugin` for advanced resource-aware customization.
 *
 * Note: plugin objects like `MyReleasePlugin` must live in `project/MyReleasePlugin.scala`
 * to be discovered by sbt. The object below is an example to copy there.
 */
object CustomStepExamples {

  private val releaseCompletedKey = AttributeKey[Boolean]("releaseCompleted")

  // ── Hook-first customization (preferred) ────────────────────────────

  /** First customization: keep the compiled built-ins, disable push, and add one lifecycle hook.
    *
    * Usage in build.sbt:
    * {{{
    * import io.release.ReleasePluginIO.autoImport.*
    * import io.release.ReleaseHookIO
    * import io.release.examples.CustomStepExamples
    *
    * lazy val root = (project in file("."))
    *   .settings(CustomStepExamples.firstHookSettings)
    * }}}
    *
    * Run with: `sbt "releaseIO with-defaults"`
    */
  lazy val firstHookSettings: Seq[Setting[?]] = Seq(
    releaseIOPolicyEnablePush := false,
    releaseIOHooksBeforeTag += printBannerHook
  )

  /** A richer hook-based setup with policy toggles and a few semantic lifecycle hooks.
    *
    * Usage in build.sbt:
    * {{{
    * import io.release.ReleasePluginIO.autoImport.*
    * import io.release.examples.CustomStepExamples
    *
    * lazy val root = (project in file("."))
    *   .settings(CustomStepExamples.customHookSettings)
    * }}}
    */
  lazy val customHookSettings: Seq[Setting[?]] = Seq(
    releaseIOPolicyEnablePush := false,
    releaseIOHooksAfterCleanCheck += validateBranchHook,
    releaseIOHooksAfterVersionResolution += generateChangelogHook,
    releaseIOHooksAfterTag += optionalNotifyHook,
    releaseIOHooksAfterNextCommit += markReleaseDoneHook
  )

  /** Recommended template for a safe local rehearsal with the compiled built-ins intact.
    *
    * This disables push, publish, and run-clean, then adds one validation-oriented hook after
    * the clean-working-dir check and one lifecycle hook before tagging.
    *
    * {{{
    * lazy val root = (project in file("."))
    *   .settings(CustomStepExamples.rehearsalSettings)
    *
    * // Rehearse the planned release without side effects
    * // sbt "releaseIO check with-defaults"
    *
    * // Rehearse with explicit versions
    * // sbt "releaseIO check with-defaults release-version 1.0.0 next-version 1.1.0-SNAPSHOT"
    * }}}
    */
  lazy val rehearsalSettings: Seq[Setting[?]] = Seq(
    releaseIOPolicyEnablePush     := false,
    releaseIOPolicyEnablePublish  := false,
    releaseIOPolicyEnableRunClean := false,
    releaseIOHooksAfterCleanCheck += validateBranchHook,
    releaseIOHooksBeforeTag += printBannerHook
  )

  val printBannerHook: ReleaseHookIO = ReleaseHookIO.action("print-banner")(_ =>
    IO.println("=" * 60) *>
      IO.println("  RELEASE IN PROGRESS") *>
      IO.println("=" * 60)
  )

  val validateBranchHook: ReleaseHookIO = ReleaseHookIO.io("validate-branch")(ctx =>
    ctx.vcs match {
      case Some(vcs) =>
        vcs.currentBranch.flatMap(branch =>
          if (branch == "main" || branch == "master")
            IO.pure(ctx)
          else
            IO.raiseError(
              new RuntimeException(
                s"Releases must be done from main/master, but current branch is '$branch'"
              )
            )
        )
      case None      =>
        IO.raiseError(new RuntimeException("VCS not initialized"))
    }
  )

  /** Hook variant of the changelog example. Attach after version resolution or before
    * writing the release version so `ctx.releaseVersion` is already available.
    */
  val generateChangelogHook: ReleaseHookIO =
    ReleaseHookIO.action("generate-changelog")(ctx =>
      ctx.versions match {
        case Some((releaseVer, _)) =>
          IO.blocking {
            val baseDir  = Project.extract(ctx.state).get(thisProject).base
            val file     = new java.io.File(baseDir, "CHANGELOG.md")
            val entry    = s"\n## $releaseVer\n\n- Release $releaseVer\n"
            val existing =
              if (file.exists())
                scala.util.Using(scala.io.Source.fromFile(file))(_.mkString).get
              else "# Changelog\n"
            java.nio.file.Files.write(file.toPath, (existing + entry).getBytes("UTF-8"))
          } *>
            IO.println(s"${ReleaseLogPrefixes.Core} Updated CHANGELOG.md for $releaseVer")
        case None                  =>
          IO.raiseError(new RuntimeException("Versions not set"))
      }
    )

  val optionalNotifyHook: ReleaseHookIO = ReleaseHookIO.action("optional-notify")(ctx =>
    IO.blocking {
      val version = ctx.releaseVersion.getOrElse("unknown")
      ctx.state.log.info(s"${ReleaseLogPrefixes.Core} Notifying release of $version...")
    }.handleErrorWith {
      case NonFatal(err) =>
        IO.blocking(
          ctx.state.log.warn(
            s"${ReleaseLogPrefixes.Core} Notification failed: ${err.getMessage}, continuing..."
          )
        )
      case fatal         => IO.raiseError(fatal)
    }
  )

  val markReleaseDoneHook: ReleaseHookIO =
    ReleaseHookIO.io("mark-done")(ctx => IO.pure(ctx.withMetadata(releaseCompletedKey, true)))

}

// ── Resource-aware custom plugin example ─────────────────────────────

/** Placeholder for an HTTP client used in the resource-aware plugin example below. */
trait HttpClient {
  def get(path: String): String
  def post(path: String, body: String): Unit
  def close(): Unit
}

/**
 * Advanced/custom-plugin example: acquire an HTTP client once and use it in
 * resource-aware hooks while staying on compiled hook mode.
 *
 * Prefer hook/policy settings for routine customization. Extend `ReleasePluginIOLike[T]`
 * when your release flow needs a shared resource (e.g., HTTP client, database connection)
 * that is acquired once and released automatically at the end.
 *
 * Copy this object to `project/MyReleasePlugin.scala` in your build so sbt can discover it.
 * In that file, prefer `_root_.` imports (for example `_root_.io.release...`) because
 * `import sbt.*` can shadow the `io` package.
 * If you keep a package declaration in that file, import the plugin symbol in `build.sbt`
 * before calling `enablePlugins(...)`.
 *
 * Enable in build.sbt:
 * {{{
 * // if MyReleasePlugin is in the default package in project/MyReleasePlugin.scala:
 * enablePlugins(MyReleasePlugin)
 *
 * // if MyReleasePlugin has a package:
 * // import your.package.MyReleasePlugin
 * // enablePlugins(MyReleasePlugin)
 * }}}
 *
 * Run with:
 * {{{
 * sbt "releaseWithClient with-defaults release-version 1.0.0 next-version 1.1.0-SNAPSHOT"
 * }}}
 */
object MyReleasePlugin extends ReleasePluginIOLike[HttpClient] {

  override def trigger               = noTrigger
  override protected def commandName = "releaseWithClient"

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

  private val validateBranch: ReleaseResourceHookIO[HttpClient] =
    ReleaseResourceHookIO.io[HttpClient]("validate-branch")(client =>
      ctx =>
        IO.blocking(client.get("/allowed-branches").split(",").toSet)
          .flatMap(allowed =>
            ctx.vcs match {
              case Some(vcs) =>
                vcs.currentBranch.flatMap(branch =>
                  if (!allowed.contains(branch))
                    IO.raiseError(
                      new RuntimeException(s"Branch '$branch' is not allowed for release")
                    )
                  else IO.pure(ctx)
                )
              case None      =>
                IO.raiseError(new RuntimeException("VCS not initialized"))
            }
          )
    )

  private val notifySlack: ReleaseResourceHookIO[HttpClient] =
    ReleaseResourceHookIO.action[HttpClient]("notify-slack")(client =>
      ctx =>
        IO.blocking {
          val version = ctx.releaseVersion.getOrElse("unknown")
          client.post("/slack-webhook", s"""{"text": "Tagged v${version}"}""")
        }
    )

  private val verifyPublish: ReleaseResourceHookIO[HttpClient] =
    ReleaseResourceHookIO.action[HttpClient]("verify-publish")(client =>
      ctx =>
        IO.blocking {
          val version = ctx.releaseVersion.getOrElse("unknown")
          client.get(s"/artifacts/$version")
        }.void
    )

  override protected def releaseResourceHooks(
      state: State
  ): ReleaseResourceHooks[HttpClient] =
    ReleaseResourceHooks(
      beforeVersionResolutionHooks = Seq(validateBranch),
      afterTagHooks = Seq(notifySlack),
      afterPublishHooks = Seq(verifyPublish)
    )
}
