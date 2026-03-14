package io.release.examples

import cats.effect.{IO, Resource}
import io.release.{ReleaseContext, ReleasePluginIOLike, ReleaseStepIO}
import io.release.steps.ReleaseSteps
import sbt.*
import sbt.Keys.thisProject

import scala.util.control.NonFatal

/**
 * Examples showing how to create custom release steps and compose them
 * with the built-in steps.
 *
 * '''How to read this file (recommended path):'''
 *  1. Start with `firstCustomProcess` for a working setup with one custom step.
 *  2. Browse the individual step examples to learn each API pattern.
 *  3. Use `MyReleasePlugin` for advanced resource-aware customization.
 *
 * Note: plugin objects like `MyReleasePlugin` must live in `project/MyReleasePlugin.scala`
 * to be discovered by sbt. The object below is an example to copy there.
 */
object CustomStepExamples {

  private val releaseCompletedKey = AttributeKey[Boolean]("releaseCompleted")

  // ── Getting started ──────────────────────────────────────────────────

  /** First customization: prepend one custom step and keep the default flow.
    *
    * Usage in build.sbt:
    * {{{
    * import io.release.ReleasePluginIO.autoImport._
    * import io.release.examples.CustomStepExamples
    * releaseIOProcess := CustomStepExamples.firstCustomProcess
    * }}}
    *
    * Run with: `sbt "releaseIO with-defaults"`
    */
  lazy val firstCustomProcess: Seq[ReleaseStepIO] = Seq(printBanner) ++ ReleaseSteps.defaults

  // ── Individual step examples ─────────────────────────────────────────

  /** Custom step using `ReleaseStepIO.io` with pure console output. */
  val printBanner: ReleaseStepIO = ReleaseStepIO.io("print-banner") { ctx =>
    IO.println("=" * 60) *>
      IO.println("  RELEASE IN PROGRESS") *>
      IO.println("=" * 60).as(ctx)
  }

  /** Custom step using `ReleaseStepIO.pure` for a side-effect-free context transformation. */
  val markReleaseDone: ReleaseStepIO = ReleaseStepIO.pure("mark-done") { ctx =>
    ctx.withMetadata(releaseCompletedKey, true)
  }

  /** Custom step using `ReleaseStepIO.io` with VCS access and error handling via `IO.raiseError`. */
  val validateBranch: ReleaseStepIO = ReleaseStepIO.io("validate-branch") { ctx =>
    ctx.vcs match {
      case Some(vcs) =>
        vcs.currentBranch.flatMap { branch =>
          if (branch == "main" || branch == "master")
            IO.pure(ctx)
          else
            IO.raiseError(
              new RuntimeException(
                s"Releases must be done from main/master, but current branch is '$branch'"
              )
            )
        }
      case None      =>
        IO.raiseError(new RuntimeException("VCS not initialized"))
    }
  }

  /** Error recovery: use `.handleErrorWith` with `NonFatal` to log a warning and continue
    * instead of aborting the release. Useful for non-critical steps like notifications.
    */
  val optionalNotify: ReleaseStepIO = ReleaseStepIO.io("optional-notify") { ctx =>
    IO.blocking {
      // Replace with real notification logic (e.g., Slack webhook, email)
      val version = ctx.releaseVersion.getOrElse("unknown")
      ctx.state.log.info(s"[release-io] Notifying release of $version...")
    }.as(ctx)
      .handleErrorWith {
        case NonFatal(err) =>
          IO.blocking(
            ctx.state.log.warn(
              s"[release-io] Notification failed: ${err.getMessage}, continuing..."
            )
          ).as(ctx)
        case fatal         => IO.raiseError(fatal)
      }
  }

  /** Custom step using `IO.blocking` for shell execution.
    * Demo helper only: avoid passing untrusted shell strings in production.
    */
  def runShellCommand(name: String, command: String): ReleaseStepIO =
    ReleaseStepIO.io(s"shell-$name") { ctx =>
      IO.blocking {
        import scala.sys.process._
        command.!
      }.flatMap { exitCode =>
        if (exitCode != 0)
          IO.raiseError(
            new RuntimeException(s"Command '$command' failed with exit code $exitCode")
          )
        else IO.pure(ctx)
      }
    }

  /** Custom step accessing `ctx.versions` and `Project.extract(ctx.state)` for sbt settings.
    * Intentionally simple demo logic: append-only and not fully idempotent.
    */
  val generateChangelog: ReleaseStepIO = ReleaseStepIO.io("generate-changelog") { ctx =>
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
          IO.println(s"[release-io] Updated CHANGELOG.md for $releaseVer").as(ctx)
      case None                  =>
        IO.raiseError(new RuntimeException("Versions not set"))
    }
  }

  /** State modification: use `ctx.withState` to modify sbt state mid-release.
    * Here we add a manifest attribute to packaged jars with the release version.
    */
  val addReleaseManifestEntry: ReleaseStepIO = ReleaseStepIO.io("add-manifest-entry") { ctx =>
    IO.blocking {
      val version   = ctx.releaseVersion.getOrElse("unknown")
      val extracted = Project.extract(ctx.state)
      val newState  = extracted.appendWithSession(
        Seq(
          extracted.currentRef / Keys.packageOptions +=
            Package.ManifestAttributes("Release-Version" -> version)
        ),
        ctx.state
      )
      ctx.withState(newState)
    }
  }

  /** Cross-build step: construct `ReleaseStepIO(...)` directly with `enableCrossBuild = true`
    * so the step runs once per `crossScalaVersions` when cross-building is active.
    *
    * For task-based steps, prefer the shorthand:
    * `ReleaseStepIO.fromTask(Keys.test, enableCrossBuild = true)`
    */
  val crossBuildTest: ReleaseStepIO = ReleaseStepIO(
    name = "cross-build-test",
    execute = ctx =>
      IO.blocking {
        val scalaVer = Project.extract(ctx.state).get(Keys.scalaVersion)
        ctx.state.log.info(s"[release-io] Running tests for Scala $scalaVer")
        ctx
      },
    enableCrossBuild = true
  )

  // ── Composing a custom release process ───────────────────────────────

  /**
   * Example: a custom release process that adds a banner, validates the branch,
   * generates a changelog, and skips push.
   *
   * Usage in build.sbt:
   * {{{
   * import io.release.ReleasePluginIO.autoImport._
   * import io.release.examples.CustomStepExamples
   *
   * releaseIOProcess := CustomStepExamples.customProcess
   * }}}
   *
   * Run with: `sbt "releaseIO with-defaults"`
   */
  val customProcess: Seq[ReleaseStepIO] = Seq(
    printBanner,
    ReleaseSteps.initializeVcs,
    validateBranch,
    ReleaseSteps.checkCleanWorkingDir,
    ReleaseSteps.checkSnapshotDependencies,
    ReleaseSteps.inquireVersions,
    generateChangelog,
    ReleaseSteps.runClean,
    ReleaseSteps.runTests, // or crossBuildTest for per-Scala-version runs
    ReleaseSteps.setReleaseVersion,
    addReleaseManifestEntry,
    ReleaseSteps.commitReleaseVersion,
    ReleaseSteps.tagRelease,
    optionalNotify,
    ReleaseSteps.publishArtifacts,
    ReleaseSteps.setNextVersion,
    ReleaseSteps.commitNextVersion,
    markReleaseDone
    // Note: pushChanges intentionally omitted — push manually after verifying
  )

  // ── Utilities ────────────────────────────────────────────────────────

  /** Wraps a step so it only runs when `condition` returns true.
    *
    * Example:
    * {{{
    * conditionalStep("publish",
    *   _.releaseVersion.isDefined,
    *   ReleaseSteps.publishArtifacts)
    * }}}
    */
  def conditionalStep(
      name: String,
      condition: ReleaseContext => Boolean,
      step: ReleaseStepIO
  ): ReleaseStepIO =
    ReleaseStepIO.io(s"conditional-$name") { ctx =>
      if (condition(ctx)) step.execute(ctx)
      else IO.println(s"[release-io] Skipping $name (condition not met)").as(ctx)
    }

}

// ── Resource-aware custom plugin example ─────────────────────────────

/** Placeholder for an HTTP client used in the resource-aware plugin example below. */
trait HttpClient {
  def get(path: String): String
  def post(path: String, body: String): Unit
  def close(): Unit
}

/**
 * Example: a custom release plugin that acquires an HTTP client once
 * and uses it in resource-aware steps at non-adjacent positions.
 *
 * Extend `ReleasePluginIOLike[T]` instead of setting `releaseIOProcess` directly when your
 * release steps need a shared resource (e.g., HTTP client, database connection) that is
 * acquired once and released automatically at the end.
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

  override protected def releaseProcess(state: State): Seq[HttpClient => ReleaseStepIO] =
    Seq(
      // Plain steps — lifted automatically via the implicit conversion
      ReleaseSteps.initializeVcs,
      ReleaseSteps.checkCleanWorkingDir,

      // 1st resource step — validate branch via API (raises error or succeeds)
      resourceStepAction[HttpClient]("validate-branch") { client => ctx =>
        IO.blocking(client.get("/allowed-branches").split(",").toSet).flatMap { allowed =>
          ctx.vcs match {
            case Some(vcs) =>
              vcs.currentBranch.flatMap { branch =>
                if (!allowed.contains(branch))
                  IO.raiseError(
                    new RuntimeException(s"Branch '$branch' is not allowed for release")
                  )
                else IO.unit
              }
            case None      =>
              IO.raiseError(new RuntimeException("VCS not initialized"))
          }
        }
      },

      ReleaseSteps.checkSnapshotDependencies,
      ReleaseSteps.inquireVersions,
      ReleaseSteps.runClean,
      ReleaseSteps.runTests,
      ReleaseSteps.setReleaseVersion,
      ReleaseSteps.commitReleaseVersion,
      ReleaseSteps.tagRelease,

      // 2nd resource step — notify Slack after tagging (side-effect only, context unchanged)
      resourceStepAction[HttpClient]("notify-slack") { client => ctx =>
        IO.blocking {
          val version = ctx.releaseVersion.getOrElse("unknown")
          client.post("/slack-webhook", s"""{"text": "Tagged v${version}"}""")
        }
      },

      ReleaseSteps.publishArtifacts,

      // 3rd resource step — verify the published artifact (side-effect only, context unchanged)
      resourceStepAction[HttpClient]("verify-publish") { client => ctx =>
        IO.blocking {
          val version = ctx.releaseVersion.getOrElse("unknown")
          client.get(s"/artifacts/$version")
          ()
        }
      },

      ReleaseSteps.setNextVersion,
      ReleaseSteps.commitNextVersion,
      ReleaseSteps.pushChanges
    )
}
