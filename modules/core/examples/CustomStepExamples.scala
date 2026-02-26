package io.release.examples

import cats.effect.{IO, Resource}
import io.release.{ReleaseContext, ReleasePluginIOLike, ReleaseStepIO}
import io.release.ReleaseIO.*
import io.release.steps.ReleaseSteps
import sbt.*
import sbt.Keys.thisProject
import sbt.Project.extract

/**
 * Examples showing how to create custom release steps and compose them
 * with the built-in steps.
 */
object CustomStepExamples {

  /** Minimal working setup.
    *
    * Usage in build.sbt:
    * {{{
    * import io.release.examples.CustomStepExamples
    * releaseIOProcess := CustomStepExamples.minimalProcess
    * }}}
    *
    * Run with: `sbt "releaseIO with-defaults"`
    */
  val minimalProcess: Seq[ReleaseStepIO] = ReleaseSteps.defaults

  // --- Custom step: print a banner ---

  val printBanner: ReleaseStepIO = ReleaseStepIO.io("print-banner") { ctx =>
    IO.println("=" * 60) *>
      IO.println("  RELEASE IN PROGRESS") *>
      IO.println("=" * 60) *>
      IO.pure(ctx)
  }

  // --- Custom step: validate branch name ---

  val validateBranch: ReleaseStepIO = ReleaseStepIO.io("validate-branch") { ctx =>
    ctx.vcs match {
      case Some(vcs) =>
        for {
          branch <- IO.blocking(vcs.currentBranch)
          result <- if (branch == "main" || branch == "master")
                      IO.pure(ctx)
                    else
                      IO.raiseError(
                        new RuntimeException(
                          s"Releases must be done from main/master, but current branch is '$branch'"
                        )
                      )
        } yield result
      case None      =>
        IO.raiseError(new RuntimeException("VCS not initialized"))
    }
  }

  // --- Custom step: run a shell command ---

  def runShellCommand(name: String, command: String): ReleaseStepIO =
    ReleaseStepIO.io(s"shell-$name") { ctx =>
      IO.blocking {
        import scala.sys.process._
        val exitCode = command.!
        if (exitCode != 0)
          throw new RuntimeException(s"Command '$command' failed with exit code $exitCode")
        ctx
      }
    }

  // --- Custom step: write a changelog placeholder ---

  val generateChangelog: ReleaseStepIO = ReleaseStepIO.io("generate-changelog") { ctx =>
    ctx.versions match {
      case Some((releaseVer, _)) =>
        IO.blocking {
          val baseDir  = extract(ctx.state).get(thisProject).base
          val file     = new java.io.File(baseDir, "CHANGELOG.md")
          val entry    = s"\n## $releaseVer\n\n- Release $releaseVer\n"
          val existing =
            if (file.exists())
              scala.util.Using(scala.io.Source.fromFile(file))(_.mkString).get
            else "# Changelog\n"
          java.nio.file.Files.write(file.toPath, (existing + entry).getBytes("UTF-8"))
        } *>
          IO.println(s"[release-io] Updated CHANGELOG.md for $releaseVer") *>
          IO.pure(ctx)
      case None                  =>
        IO.raiseError(new RuntimeException("Versions not set"))
    }
  }

  // --- Custom step: pure transformation ---

  val markReleaseDone: ReleaseStepIO = ReleaseStepIO.pure("mark-done") { ctx =>
    ctx.withAttr("release-completed", "true")
  }

  // --- Composing a custom release process ---

  /**
   * Example: a custom release process that adds a banner, validates the branch,
   * generates a changelog, and skips push.
   *
   * Usage in build.sbt:
   * {{{
   * import io.release.examples.CustomStepExamples
   *
   * releaseIOProcess := CustomStepExamples.customProcess
   * }}}
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
    ReleaseSteps.runTests,
    ReleaseSteps.setReleaseVersion,
    ReleaseSteps.commitReleaseVersion,
    ReleaseSteps.tagRelease,
    ReleaseSteps.publishArtifacts,
    ReleaseSteps.setNextVersion,
    ReleaseSteps.commitNextVersion,
    markReleaseDone
    // Note: pushChanges intentionally omitted — push manually after verifying
  )

  // --- Example: conditional step ---

  def conditionalStep(
      name: String,
      condition: ReleaseContext => Boolean,
      step: ReleaseStepIO
  ): ReleaseStepIO =
    ReleaseStepIO.io(s"conditional-$name") { ctx =>
      if (condition(ctx)) step.action(ctx)
      else IO.println(s"[release-io] Skipping $name (condition not met)").as(ctx)
    }

}

// --- Resource-aware custom plugin example ---

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
 * Must be defined in a `.scala` file under `project/` so sbt discovers the AutoPlugin.
 * Use `_root_.io.release` imports because `import sbt.*` shadows the `io` package.
 *
 * Enable in build.sbt:
 * {{{
 * enablePlugins(MyReleasePlugin)
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
      // 1st resource step — validate branch via API
      resourceStep[HttpClient]("validate-branch") { client => ctx =>
        IO.blocking {
          val allowed = client.get("/allowed-branches").split(",").toSet
          ctx.vcs match {
            case Some(vcs) =>
              val branch = vcs.currentBranch
              if (!allowed.contains(branch))
                throw new RuntimeException(s"Branch '$branch' is not allowed for release")
            case None      =>
              throw new RuntimeException("VCS not initialized")
          }
          ctx
        }
      },
      ReleaseSteps.checkSnapshotDependencies,
      ReleaseSteps.inquireVersions,
      ReleaseSteps.runClean,
      ReleaseSteps.runTests,
      ReleaseSteps.setReleaseVersion,
      ReleaseSteps.commitReleaseVersion,
      ReleaseSteps.tagRelease,
      // 2nd resource step — notify Slack after tagging
      resourceStep[HttpClient]("notify-slack") { client => ctx =>
        IO.blocking {
          val version = ctx.versions.map(_._1).getOrElse("unknown")
          client.post("/slack-webhook", s"""{"text": "Tagged v${version}"}""")
          ctx
        }
      },
      ReleaseSteps.publishArtifacts,
      // 3rd resource step — verify the published artifact
      resourceStep[HttpClient]("verify-publish") { client => ctx =>
        IO.blocking {
          val version = ctx.versions.map(_._1).getOrElse("unknown")
          client.get(s"/artifacts/$version")
          ctx
        }
      },
      ReleaseSteps.setNextVersion,
      ReleaseSteps.commitNextVersion,
      ReleaseSteps.pushChanges
    )
}
