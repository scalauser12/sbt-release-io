package io.release.steps

import cats.effect.IO
import io.release.vcs.Vcs
import io.release.{ReleaseContext, ReleaseKeys, ReleaseStepIO}
import sbt.*
import sbt.Keys.*
import sbt.Project.extract
import sbtrelease.ReleasePlugin.autoImport.*
import sbtrelease.ReleaseStateTransformations.*

/** Built-in release steps composed as IO actions. */
object ReleaseSteps {

  val initializeVcs: ReleaseStepIO = ReleaseStepIO.io("initialize-vcs") { ctx =>
    val baseDir = extract(ctx.state).get(thisProject).base
    Vcs.detect(baseDir).map(v => ctx.withVcs(v))
  }

  val checkCleanWorkingDir: ReleaseStepIO = ReleaseStepIO(
    name = "check-clean-working-dir",
    action = ctx =>
      requireVcs(ctx) { vcs =>
        vcs.isClean.flatMap {
          case true => IO.pure(ctx)
          case false =>
            IO.raiseError(
              new RuntimeException(
                "Working directory is not clean. Please commit or stash your changes before releasing."
              )
            )
        }
      }
  )

  val checkSnapshotDependencies: ReleaseStepIO = ReleaseStepIO(
    name = "check-snapshot-dependencies",
    action = ctx => IO.pure(ctx),
    check = ctx =>
      IO {
        val extracted = extract(ctx.state)
        val allProjectRefs = extracted.structure.allProjectRefs

        val snapshotDeps = allProjectRefs.flatMap { projectRef =>
          val deps = scala.util
            .Try(extracted.get(projectRef / libraryDependencies))
            .getOrElse(Nil)
          deps.filter(_.revision.endsWith("-SNAPSHOT")).map { dep =>
            (projectRef.project, dep)
          }
        }

        if (snapshotDeps.nonEmpty) {
          val depList = snapshotDeps
            .map { case (proj, dep) =>
              s"  [$proj] ${dep.organization}:${dep.name}:${dep.revision}"
            }
            .mkString("\n")
          throw new RuntimeException(s"Snapshot dependencies found:\n$depList")
        }
        ctx
      },
    enableCrossBuild = true
  )

  val inquireVersions: ReleaseStepIO = ReleaseStepIO.io("inquire-versions") {
    ctx =>
      IO {
        val extracted = extract(ctx.state)
        val currentVer = extracted.get(version)

        // Use upstream sbt-release's version functions which respect releaseVersionBump setting
        val releaseFunc = extracted.runTask(releaseVersion, ctx.state)._2
        val nextFunc = extracted.runTask(releaseNextVersion, ctx.state)._2

        val suggestedReleaseVer = releaseFunc(currentVer)
        val suggestedNextVer = nextFunc(suggestedReleaseVer)

        // Allow command-line overrides
        val releaseVersionArg =
          ctx.state.get(ReleaseKeys.commandLineReleaseVersion).flatten
        val nextVersionArg =
          ctx.state.get(ReleaseKeys.commandLineNextVersion).flatten

        val finalReleaseVer = releaseVersionArg.getOrElse(suggestedReleaseVer)
        val finalNextVer = nextVersionArg.getOrElse(suggestedNextVer)

        ctx.state.log.info(s"[release-io] Current version : $currentVer")
        ctx.state.log.info(s"[release-io] Release version : $finalReleaseVer")
        ctx.state.log.info(s"[release-io] Next version    : $finalNextVer")

        ctx.withVersions(finalReleaseVer, finalNextVer)
      }
  }

  val runTests: ReleaseStepIO = ReleaseStepIO(
    name = "run-tests",
    action = ctx =>
      if (ctx.skipTests) {
        IO(ctx.state.log.info("[release-io] Skipping tests")).as(ctx)
      } else {
        IO {
          val extracted = extract(ctx.state)
          val (newState, _) =
            extracted.runTask(sbt.Test / sbt.Keys.test, ctx.state)
          ctx.copy(state = newState)
        }
      },
    enableCrossBuild = true
  )

  val setReleaseVersion: ReleaseStepIO =
    ReleaseStepIO.io("set-release-version") { ctx =>
      requireVersions(ctx) { case (releaseVer, _) =>
        writeVersion(ctx, releaseVer)
      }
    }

  val commitReleaseVersion: ReleaseStepIO = ReleaseStepIO(
    name = "commit-release-version",
    action = ctx =>
      requireVcs(ctx) { vcs =>
        requireVersions(ctx) { case (releaseVer, _) =>
          IO {
            val extracted = extract(ctx.state)
            val versionFile = extracted.get(releaseVersionFile)
            versionFile.getName
          }.flatMap { fileName =>
            vcs.add(fileName) *>
              vcs.commit(s"Setting version to $releaseVer").as(ctx)
          }
        }
      },
    check = ctx =>
      requireVcs(ctx) { _ =>
        requireVersions(ctx) { _ =>
          IO.pure(ctx)
        }
      }
  )

  val tagRelease: ReleaseStepIO = ReleaseStepIO.io("tag-release") { ctx =>
    requireVcs(ctx) { vcs =>
      requireVersions(ctx) { case (releaseVer, _) =>
        val tagName = s"v$releaseVer"
        vcs
          .tag(tagName, Some(s"Release $releaseVer"))
          .as(
            ctx.withAttr("release-tag", tagName)
          )
      }
    }
  }

  val publishArtifacts: ReleaseStepIO = ReleaseStepIO(
    name = "publish-artifacts",
    action = ctx =>
      if (ctx.skipPublish) {
        IO(ctx.state.log.info("[release-io] Skipping publish")).as(ctx)
      } else {
        IO {
          val extracted = extract(ctx.state)
          val (newState, _) = extracted.runTask(sbt.Keys.publish, ctx.state)
          ctx.copy(state = newState)
        }
      },
    check = ctx =>
      if (ctx.skipPublish) {
        IO.pure(ctx)
      } else {
        IO {
          val extracted = extract(ctx.state)
          // Check will be performed during action phase when publishTo is resolved
          ctx
        }
      },
    enableCrossBuild = true
  )

  val setNextVersion: ReleaseStepIO = ReleaseStepIO.io("set-next-version") {
    ctx =>
      requireVersions(ctx) { case (_, nextVer) =>
        writeVersion(ctx, nextVer)
      }
  }

  val commitNextVersion: ReleaseStepIO =
    ReleaseStepIO.io("commit-next-version") { ctx =>
      requireVcs(ctx) { vcs =>
        requireVersions(ctx) { case (_, nextVer) =>
          IO {
            val extracted = extract(ctx.state)
            val versionFile = extracted.get(releaseVersionFile)
            versionFile.getName
          }.flatMap { fileName =>
            vcs.add(fileName) *>
              vcs.commit(s"Setting version to $nextVer").as(ctx)
          }
        }
      }
    }

  val pushChanges: ReleaseStepIO = ReleaseStepIO.io("push-changes") { ctx =>
    requireVcs(ctx) { vcs =>
      vcs.pushAll.as(ctx)
    }
  }

  /** Default ordered sequence of all release steps using IO-native implementations.
    * These steps provide richer error handling and use the ReleaseContext-based VCS/version plumbing.
    */
  val defaults: Seq[ReleaseStepIO] = Seq(
    initializeVcs,
    checkCleanWorkingDir,
    checkSnapshotDependencies,
    inquireVersions,
    runTests,
    setReleaseVersion,
    commitReleaseVersion,
    tagRelease,
    publishArtifacts,
    setNextVersion,
    commitNextVersion,
    pushChanges
  )

  /** Alternative release process that delegates to upstream sbt-release's built-in steps.
    * Uses automatic conversion from ReleaseStep to ReleaseStepIO via SbtReleaseCompat.
    * Choose this if you want maximum compatibility with sbt-release's battle-tested implementations.
    */
  val defaultsFromUpstream: Seq[ReleaseStepIO] = {
    import _root_.io.release.SbtReleaseCompat.releaseStepToReleaseStepIO
    Seq(
      checkSnapshotDependencies,
      inquireVersions,
      runClean,
      runTest,
      setReleaseVersion,
      commitReleaseVersion,
      tagRelease,
      publishArtifacts,
      setNextVersion,
      commitNextVersion,
      pushChanges
    )
  }

  // --- helpers ---

  private def requireVcs(
      ctx: ReleaseContext
  )(f: Vcs => IO[ReleaseContext]): IO[ReleaseContext] =
    ctx.vcs match {
      case Some(v) => f(v)
      case None =>
        IO.raiseError(
          new RuntimeException(
            "VCS not initialized. Ensure initializeVcs runs before this step."
          )
        )
    }

  private def requireVersions(ctx: ReleaseContext)(
      f: ((String, String)) => IO[ReleaseContext]
  ): IO[ReleaseContext] =
    ctx.versions match {
      case Some(v) => f(v)
      case None =>
        IO.raiseError(
          new RuntimeException(
            "Versions not set. Ensure inquireVersions runs before this step."
          )
        )
    }

  private def writeVersion(
      ctx: ReleaseContext,
      ver: String
  ): IO[ReleaseContext] = IO {
    val extracted = extract(ctx.state)

    // Use upstream sbt-release's settings for file location and scope
    val versionFile = extracted.get(releaseVersionFile)
    val useGlobalVersion = extracted.get(releaseUseGlobalVersion)

    val versionKey = if (useGlobalVersion) "ThisBuild / version" else "version"
    val contents = s"""$versionKey := "$ver"\n"""

    java.nio.file.Files.write(versionFile.toPath, contents.getBytes("UTF-8"))
    ctx.state.log.info(
      s"[release-io] Wrote version $ver to ${versionFile.getName}"
    )

    val versionSetting = if (useGlobalVersion) {
      ThisBuild / version := ver
    } else {
      version := ver
    }

    val newState = extracted.appendWithSession(
      Seq(versionSetting),
      ctx.state
    )
    ctx.copy(state = newState)
  }
}
