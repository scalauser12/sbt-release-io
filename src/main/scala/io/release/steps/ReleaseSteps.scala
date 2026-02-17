package io.release.steps

import cats.effect.IO
import io.release.{ReleaseContext, ReleaseStepIO}
import io.release.vcs.Vcs
import io.release.version.Version
import sbt._
import sbt.Keys._
import sbt.Project.extract

/** Built-in release steps composed as IO actions. */
object ReleaseSteps {

  val initializeVcs: ReleaseStepIO = ReleaseStepIO.io("initialize-vcs") { ctx =>
    val baseDir = extract(ctx.state).get(thisProject).base
    Vcs.detect(baseDir).map(v => ctx.withVcs(v))
  }

  val checkCleanWorkingDir: ReleaseStepIO = ReleaseStepIO.io("check-clean-working-dir") { ctx =>
    requireVcs(ctx) { vcs =>
      vcs.isClean.flatMap {
        case true  => IO.pure(ctx)
        case false =>
          IO.raiseError(new RuntimeException(
            "Working directory is not clean. Please commit or stash your changes before releasing."
          ))
      }
    }
  }

  val checkSnapshotDependencies: ReleaseStepIO = ReleaseStepIO.io("check-snapshot-dependencies") { ctx =>
    IO {
      val extracted = extract(ctx.state)
      val snapshotDeps = extracted.get(libraryDependencies).filter { dep =>
        dep.revision.endsWith("-SNAPSHOT")
      }
      if (snapshotDeps.nonEmpty) {
        val depList = snapshotDeps.map(d => s"  ${d.organization}:${d.name}:${d.revision}").mkString("\n")
        throw new RuntimeException(s"Snapshot dependencies found:\n$depList")
      }
      ctx
    }
  }

  val inquireVersions: ReleaseStepIO = ReleaseStepIO.io("inquire-versions") { ctx =>
    IO {
      val extracted = extract(ctx.state)
      val currentVer = extracted.get(version)
      val parsed = Version.parse(currentVer).getOrElse(
        throw new RuntimeException(s"Cannot parse current version: $currentVer")
      )
      val releaseVer = Version.releaseVersion(parsed).string
      val nextVer = Version.nextVersion(parsed).string

      println(s"[release-io] Current version : $currentVer")
      println(s"[release-io] Release version : $releaseVer")
      println(s"[release-io] Next version    : $nextVer")

      ctx.withVersions(releaseVer, nextVer)
    }
  }

  val runTests: ReleaseStepIO = ReleaseStepIO.io("run-tests") { ctx =>
    if (ctx.skipTests) {
      IO(println("[release-io] Skipping tests")).as(ctx)
    } else {
      IO {
        val extracted = extract(ctx.state)
        val (newState, _) = extracted.runTask(sbt.Test / sbt.Keys.test, ctx.state)
        ctx.copy(state = newState)
      }
    }
  }

  val setReleaseVersion: ReleaseStepIO = ReleaseStepIO.io("set-release-version") { ctx =>
    requireVersions(ctx) { case (releaseVer, _) =>
      writeVersion(ctx, releaseVer)
    }
  }

  val commitReleaseVersion: ReleaseStepIO = ReleaseStepIO.io("commit-release-version") { ctx =>
    requireVcs(ctx) { vcs =>
      requireVersions(ctx) { case (releaseVer, _) =>
        vcs.add("version.sbt") *>
          vcs.commit(s"Setting version to $releaseVer").as(ctx)
      }
    }
  }

  val tagRelease: ReleaseStepIO = ReleaseStepIO.io("tag-release") { ctx =>
    requireVcs(ctx) { vcs =>
      requireVersions(ctx) { case (releaseVer, _) =>
        val tagName = s"v$releaseVer"
        vcs.tag(tagName, Some(s"Release $releaseVer")).as(
          ctx.withAttr("release-tag", tagName)
        )
      }
    }
  }

  val publishArtifacts: ReleaseStepIO = ReleaseStepIO.io("publish-artifacts") { ctx =>
    if (ctx.skipPublish) {
      IO(println("[release-io] Skipping publish")).as(ctx)
    } else {
      IO {
        val extracted = extract(ctx.state)
        val (newState, _) = extracted.runTask(sbt.Keys.publish, ctx.state)
        ctx.copy(state = newState)
      }
    }
  }

  val setNextVersion: ReleaseStepIO = ReleaseStepIO.io("set-next-version") { ctx =>
    requireVersions(ctx) { case (_, nextVer) =>
      writeVersion(ctx, nextVer)
    }
  }

  val commitNextVersion: ReleaseStepIO = ReleaseStepIO.io("commit-next-version") { ctx =>
    requireVcs(ctx) { vcs =>
      requireVersions(ctx) { case (_, nextVer) =>
        vcs.add("version.sbt") *>
          vcs.commit(s"Setting version to $nextVer").as(ctx)
      }
    }
  }

  val pushChanges: ReleaseStepIO = ReleaseStepIO.io("push-changes") { ctx =>
    requireVcs(ctx) { vcs =>
      vcs.pushAll.as(ctx)
    }
  }

  /** Default ordered sequence of all release steps. */
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

  // --- helpers ---

  private def requireVcs(ctx: ReleaseContext)(f: Vcs => IO[ReleaseContext]): IO[ReleaseContext] =
    ctx.vcs match {
      case Some(v) => f(v)
      case None    => IO.raiseError(new RuntimeException(
        "VCS not initialized. Ensure initializeVcs runs before this step."
      ))
    }

  private def requireVersions(ctx: ReleaseContext)(
      f: ((String, String)) => IO[ReleaseContext]
  ): IO[ReleaseContext] =
    ctx.versions match {
      case Some(v) => f(v)
      case None    => IO.raiseError(new RuntimeException(
        "Versions not set. Ensure inquireVersions runs before this step."
      ))
    }

  private def writeVersion(ctx: ReleaseContext, ver: String): IO[ReleaseContext] = IO {
    val extracted = extract(ctx.state)
    val baseDir = extracted.get(thisProject).base
    val versionFile = new java.io.File(baseDir, "version.sbt")
    val contents = s"""ThisBuild / version := "$ver"\n"""
    java.nio.file.Files.write(versionFile.toPath, contents.getBytes("UTF-8"))
    println(s"[release-io] Wrote version $ver to version.sbt")

    val newState = extracted.appendWithSession(
      Seq(ThisBuild / version := ver),
      ctx.state
    )
    ctx.copy(state = newState)
  }
}
