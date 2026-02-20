package io.release.examples

import cats.effect.IO
import io.release.{ReleaseContext, ReleaseStepIO}
import io.release.steps.ReleaseSteps
import sbt.Project.extract
import sbt.Keys.thisProject

/**
 * Examples showing how to create custom release steps and compose them
 * with the built-in steps.
 */
object CustomStepExamples {

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

  // --- Example: Mixing upstream sbt-release steps with custom IO steps ---

  /**
   * Example showing how to combine upstream sbt-release transformations
   * with custom IO-based steps for maximum flexibility.
   *
   * Usage in build.sbt:
   * {{{
   * import io.release.examples.CustomStepExamples
   *
   * releaseIOProcess := CustomStepExamples.mixedProcess
   * }}}
   */
  val mixedProcess: Seq[ReleaseStepIO] = {
    import sbtrelease.ReleaseStateTransformations._
    import _root_.io.release.SbtReleaseCompat.releaseStepToReleaseStepIO

    Seq(
      printBanner,                // Custom IO step
      checkSnapshotDependencies,  // Upstream sbt-release step (auto-converted)
      ReleaseSteps.initializeVcs, // IO-native step
      validateBranch,             // Custom validation step
      inquireVersions,            // Upstream step with configurable version bumping
      ReleaseSteps.runTests,      // IO-native test runner
      setReleaseVersion,          // Upstream version setter
      generateChangelog,          // Custom changelog generator
      commitReleaseVersion,       // Upstream commit step
      tagRelease,                 // Upstream tag step
      publishArtifacts,           // Upstream publish step
      setNextVersion,             // Upstream next version
      commitNextVersion,          // Upstream commit
      markReleaseDone             // Custom completion marker
      // Note: pushChanges intentionally omitted
    )
  }
}
