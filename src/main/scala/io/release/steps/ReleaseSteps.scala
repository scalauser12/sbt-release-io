package io.release.steps

import cats.effect.IO
import scala.sys.process.*
import io.release.{ReleaseContext, ReleaseKeys, ReleaseStepIO}
import sbt.*
import sbt.Keys.*
import sbt.Project.extract
import sbt.Package.ManifestAttributes
import sbtrelease.ReleasePlugin.autoImport.*
import sbtrelease.ReleaseStateTransformations.{runClean, runTest}
import sbtrelease.Vcs

/** Built-in release steps composed as IO actions. */
object ReleaseSteps {

  val initializeVcs: ReleaseStepIO = ReleaseStepIO.io("initialize-vcs") { ctx =>
    val baseDir = extract(ctx.state).get(thisProject).base
    IO.blocking(Vcs.detect(baseDir)).flatMap {
      case Some(sbtVcs) =>
        IO.blocking {
          // Set releaseVcs in state so delegated sbt-release steps can access it at runtime
          val newState = extract(ctx.state).appendWithSession(
            Seq(releaseVcs := Some(sbtVcs)),
            ctx.state
          )
          ctx.copy(state = newState).withVcs(sbtVcs)
        }
      case None         =>
        IO.raiseError(new RuntimeException(s"No VCS detected at ${baseDir.getAbsolutePath}"))
    }
  }

  val checkSnapshotDependencies: ReleaseStepIO = ReleaseStepIO(
    name = "check-snapshot-dependencies",
    action = ctx => IO.pure(ctx),
    check = ctx =>
      IO.blocking {
        val extracted   = extract(ctx.state)
        val thisRef     = extracted.get(thisProjectRef)
        val (_, result) =
          sbtrelease.Compat.runTaskAggregated(thisRef / releaseSnapshotDependencies, ctx.state)
        result match {
          case Value(value) => Right(value.flatMap(_.value))
          case Inc(cause)   => Left(cause)
        }
      }.flatMap {
        case Left(cause)                  =>
          IO.raiseError(new RuntimeException("Error checking for snapshot dependencies: " + cause))
        case Right(deps) if deps.nonEmpty =>
          val depList = deps
            .map(dep => s"  ${dep.organization}:${dep.name}:${dep.revision}")
            .mkString("\n")
          IO.raiseError(new RuntimeException(s"Snapshot dependencies found:\n$depList"))
        case Right(_)                     => IO.pure(ctx)
      },
    enableCrossBuild = true
  )

  val inquireVersions: ReleaseStepIO = ReleaseStepIO.io("inquire-versions") { ctx =>
    IO.blocking {
      val extracted  = extract(ctx.state)
      val currentVer = extracted.get(version)

      // Use upstream sbt-release's version functions which respect releaseVersionBump setting
      val (s1, releaseFunc) = extracted.runTask(releaseVersion, ctx.state)
      val (s2, nextFunc)    = extracted.runTask(releaseNextVersion, s1)

      val suggestedReleaseVer = releaseFunc(currentVer)
      val suggestedNextVer    = nextFunc(suggestedReleaseVer)

      // Allow command-line overrides
      val releaseVersionArg =
        s2.get(ReleaseKeys.commandLineReleaseVersion).flatten
      val nextVersionArg    =
        s2.get(ReleaseKeys.commandLineNextVersion).flatten

      val finalReleaseVer = releaseVersionArg.getOrElse(suggestedReleaseVer)
      val finalNextVer    = nextVersionArg.getOrElse(suggestedNextVer)

      s2.log.info(s"[release-io] Current version : $currentVer")
      s2.log.info(s"[release-io] Release version : $finalReleaseVer")
      s2.log.info(s"[release-io] Next version    : $finalNextVer")

      // Store versions in both ReleaseContext and State attributes for upstream interop
      val updatedState = s2.put(ReleaseKeys.versions, (finalReleaseVer, finalNextVer))
      ctx.copy(state = updatedState).withVersions(finalReleaseVer, finalNextVer)
    }
  }

  val runTests: ReleaseStepIO = ReleaseStepIO(
    name = "run-tests",
    action = ctx =>
      if (ctx.skipTests) {
        IO(ctx.state.log.info("[release-io] Skipping tests")).as(ctx)
      } else {
        IO.blocking {
          val extracted = extract(ctx.state)
          val ref       = extracted.get(thisProjectRef)
          val newState  = extracted.runAggregated(ref / Test / test, ctx.state)
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
    check = ctx => {
      val extracted       = extract(ctx.state)
      val base            = extracted.get(thisProject).base
      val ignoreUntracked = extracted.get(releaseIgnoreUntrackedFiles)
      IO.blocking(Vcs.detect(base)).flatMap {
        case None      =>
          IO.raiseError(
            new RuntimeException(s"No VCS detected at ${base.getAbsolutePath}")
          )
        case Some(vcs) =>
          IO.blocking {
            val lines    = vcs.status.!!.trim.linesIterator
            // Filter untracked ('?') lines only when releaseIgnoreUntrackedFiles is true.
            // When false (default), untracked files are treated as dirty and block the release.
            val filtered =
              if (ignoreUntracked) lines.filterNot(_.startsWith("?")) else lines
            filtered.mkString("\n")
          }.flatMap {
            case "" => IO.pure(ctx)
            case s  => IO.raiseError(new RuntimeException(s"Working directory is dirty:\n$s"))
          }
      }
    },
    action = ctx =>
      requireVersions(ctx) { case (releaseVer, _) =>
        commitVersionNative(ctx, releaseCommitMessage).flatMap { case (resultCtx, currentHash) =>
          IO.blocking {
            val extracted        = extract(resultCtx.state)
            val useGlobalVersion = extracted.get(releaseUseGlobalVersion)
            val versionSetting   =
              if (useGlobalVersion) ThisBuild / version := releaseVer
              else version                              := releaseVer
            val newState         = extracted.appendWithSession(
              Seq(
                packageOptions += ManifestAttributes("Vcs-Release-Hash" -> currentHash),
                versionSetting
              ),
              resultCtx.state
            )
            resultCtx.copy(state = newState)
          }
        }
      }
  )

  val tagRelease: ReleaseStepIO = ReleaseStepIO.io("tag-release") { ctx =>
    requireVcs(ctx) { vcs =>
      IO.blocking {
        val extracted        = extract(ctx.state)
        val (s1, tagName)    = extracted.runTask(releaseTagName, ctx.state)
        val (s2, tagComment) = extracted.runTask(releaseTagComment, s1)
        val sign             = extracted.get(releaseVcsSign)
        val defaultAnswer    = s2.get(ReleaseKeys.tagDefault).flatten
        val useDefaults      = s2.get(ReleaseKeys.useDefaults).getOrElse(false)
        TagParams(tagName, tagComment, sign, defaultAnswer, useDefaults) -> ctx.copy(state = s2)
      }.flatMap { case (params, updatedCtx) =>
        resolveTag(vcs, params, updatedCtx)
      }
    }
  }

  private def resolveTag(
      vcs: Vcs,
      params: TagParams,
      ctx: ReleaseContext
  ): IO[ReleaseContext] = {
    val TagParams(tagName, tagComment, sign, defaultAnswer, useDefaults) = params
    IO.blocking(vcs.existsTag(tagName)).flatMap {
      case false =>
        IO.blocking {
          runProcess(vcs.tag(tagName, tagComment, sign = sign), s"vcs tag '$tagName'")
          val newState = extract(ctx.state).appendWithSession(
            Seq(packageOptions += ManifestAttributes("Vcs-Release-Tag" -> tagName)),
            ctx.state
          )
          ctx.copy(state = newState)
        }
      case true  =>
        val effectiveAnswer: IO[String] = defaultAnswer match {
          case Some(ans)           => IO.pure(ans)
          case None if useDefaults =>
            IO(
              ctx.state.log.warn(
                s"[release-io] Tag [$tagName] already exists. Aborting (use-defaults mode)."
              )
            ).as("a")
          case None                =>
            IO.print(
              s"Tag [$tagName] exists! Overwrite, keep or abort or enter a new tag (o/k/a)? [a] "
            ) *>
              IO.readLine
        }
        effectiveAnswer.flatMap {
          case "a" | "A" | "" =>
            IO.raiseError(
              new RuntimeException(s"Tag [$tagName] already exists. Aborting release!")
            )
          case "k" | "K"      =>
            // Keep existing tag: no manifest attribute (matches upstream sbt-release behavior)
            IO(
              ctx.state.log
                .warn(s"[release-io] Tag [$tagName] already exists. Keeping existing tag.")
            ).as(ctx)
          case "o" | "O"      =>
            IO(ctx.state.log.warn(s"[release-io] Tag [$tagName] already exists. Overwriting.")) *>
              IO.blocking {
                runProcess(vcs.tag(tagName, tagComment, sign = sign), s"vcs tag '$tagName'")
                val newState = extract(ctx.state).appendWithSession(
                  Seq(packageOptions += ManifestAttributes("Vcs-Release-Tag" -> tagName)),
                  ctx.state
                )
                ctx.copy(state = newState)
              }
          case newTagName     =>
            IO(
              ctx.state.log.info(s"[release-io] Tag [$tagName] exists. Trying tag [$newTagName].")
            ) *>
              resolveTag(
                vcs,
                params.copy(tagName = newTagName, defaultAnswer = None, useDefaults = false),
                ctx
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
        IO.blocking {
          val extracted = extract(ctx.state)
          val newState  =
            extracted.runAggregated(extracted.currentRef / releasePublishArtifactsAction, ctx.state)
          ctx.copy(state = newState)
        }
      },
    check = ctx =>
      if (ctx.skipPublish) IO.pure(ctx)
      else
        IO.blocking {
          val extracted = extract(ctx.state)
          val ref       = extracted.get(thisProjectRef)
          val allRefs   = ref +: extracted.currentProject.aggregate
          val missing   = allRefs
            .filterNot { r =>
              checkPublishSkip(extracted, r, ctx.state)
            }
            .filter { r =>
              checkPublishToMissing(extracted, r, ctx.state)
            }
          if (missing.nonEmpty) {
            val names = missing.map(_.project)
            throw new RuntimeException(
              s"publishTo not configured for: ${names.mkString(", ")}. " +
                "Set publishTo or add `publish / skip := true`."
            )
          }
          ctx
        },
    enableCrossBuild = true
  )

  val setNextVersion: ReleaseStepIO = ReleaseStepIO.io("set-next-version") { ctx =>
    requireVersions(ctx) { case (_, nextVer) =>
      writeVersion(ctx, nextVer)
    }
  }

  val commitNextVersion: ReleaseStepIO =
    ReleaseStepIO.io("commit-next-version") { ctx =>
      commitVersionNative(ctx, releaseNextCommitMessage).map(_._1)
    }

  val pushChanges: ReleaseStepIO = ReleaseStepIO(
    name = "push-changes",
    check = ctx => {
      val base = extract(ctx.state).get(thisProject).base
      IO.blocking(Vcs.detect(base)).flatMap {
        case None      => IO.pure(ctx)
        case Some(vcs) =>
          IO.blocking {
            if (!vcs.hasUpstream)
              throw new RuntimeException(
                s"[release-io] No tracking branch configured for branch '${vcs.currentBranch}'. " +
                  "Set up a remote tracking branch or remove pushChanges from the release process."
              )
            if (vcs.isBehindRemote)
              throw new RuntimeException(
                "[release-io] The upstream branch has unmerged commits. Merge them before releasing."
              )
          }.as(ctx)
      }
    },
    action = ctx =>
      requireVcs(ctx) { vcs =>
        IO.blocking { runProcess(vcs.pushChanges, "vcs push") }.as(ctx)
      }
  )

  /** Default ordered sequence of all release steps using IO-native implementations.
    * These steps provide richer error handling and use the ReleaseContext-based VCS/version plumbing.
    */
  val defaults: Seq[ReleaseStepIO] = Seq(
    initializeVcs,
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

  /** Alternative release sequence that replaces only `runClean` and `runTest` with upstream
    * sbt-release's built-in implementations via [[io.release.SbtReleaseCompat]]. All other steps
    * are the IO-native implementations from [[defaults]]. Use this if you want maximum
    * compatibility with sbt-release's test and clean steps.
    */
  val defaultsFromUpstream: Seq[ReleaseStepIO] = {
    import _root_.io.release.SbtReleaseCompat.releaseStepToReleaseStepIO
    Seq(
      initializeVcs,
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

  @annotation.nowarn("msg=deprecated")
  private def checkPublishSkip(
      extracted: Extracted,
      ref: ProjectRef,
      state: State
  ): Boolean =
    scala.util.Try(extracted.runTask(Keys.skip in (ref, publish), state)._2).getOrElse(false)

  @annotation.nowarn("msg=deprecated")
  private def checkPublishToMissing(
      extracted: Extracted,
      ref: ProjectRef,
      state: State
  ): Boolean =
    scala.util.Try(extracted.runTask(publishTo in ref, state)._2).getOrElse(None).isEmpty

  private final case class TagParams(
      tagName: String,
      tagComment: String,
      sign: Boolean,
      defaultAnswer: Option[String],
      useDefaults: Boolean
  )

  /** Runs a process and throws a descriptive exception on non-zero exit instead of the opaque
    * "Nonzero exit value: N" from `ProcessBuilder.!!`.
    */
  private def runProcess(process: ProcessBuilder, context: => String): Unit = {
    val code = process.!
    if (code != 0)
      throw new RuntimeException(s"$context failed with exit code $code")
  }

  private def requireVcs(
      ctx: ReleaseContext
  )(f: Vcs => IO[ReleaseContext]): IO[ReleaseContext] =
    ctx.vcs match {
      case Some(v) => f(v)
      case None    =>
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
      case None    =>
        IO.raiseError(
          new RuntimeException(
            "Versions not set. Ensure inquireVersions runs before this step."
          )
        )
    }

  private def commitVersionNative(
      ctx: ReleaseContext,
      commitMessageKey: TaskKey[String]
  ): IO[(ReleaseContext, String)] =
    ctx.vcs match {
      case None      =>
        IO.raiseError(
          new RuntimeException(
            "VCS not initialized. Ensure initializeVcs runs before this step."
          )
        )
      case Some(vcs) =>
        IO.blocking {
          val extracted    = extract(ctx.state)
          val versionFile  = extracted.get(releaseVersionFile).getCanonicalFile
          val base         = vcs.baseDir.getCanonicalFile
          val sign         = extracted.get(releaseVcsSign)
          val signOff      = extracted.get(releaseVcsSignOff)
          val relativePath = sbt.IO
            .relativize(base, versionFile)
            .getOrElse(
              throw new RuntimeException(
                s"[release-io] Version file [$versionFile] is outside of VCS root [$base]"
              )
            )

          runProcess(vcs.add(relativePath), s"vcs add '$relativePath'")

          // Always filter '?' lines: untracked files are never staged and would
          // cause a spurious 'nothing to commit' failure if included in the check.
          val status = vcs.status.!!.trim.linesIterator
            .filterNot(_.startsWith("?"))
            .mkString("\n")

          if (status.nonEmpty) {
            val (commitState, msg) = extracted.runTask(commitMessageKey, ctx.state)
            runProcess(vcs.commit(msg, sign, signOff), "vcs commit")
            (ctx.copy(state = commitState), vcs.currentHash)
          } else {
            // nothing to commit — version file content unchanged (empty-commit scenario)
            (ctx, vcs.currentHash)
          }
        }
    }

  private def writeVersion(ctx: ReleaseContext, ver: String): IO[ReleaseContext] = {
    val extracted        = extract(ctx.state)
    val versionFile      = extracted.get(releaseVersionFile)
    val useGlobalVersion = extracted.get(releaseUseGlobalVersion)
    val versionKey       = if (useGlobalVersion) "ThisBuild / version" else "version"
    val contents         = s"""$versionKey := "$ver"\n"""
    IO.blocking(java.nio.file.Files.write(versionFile.toPath, contents.getBytes("UTF-8"))) *>
      IO.blocking {
        ctx.state.log.info(s"[release-io] Wrote version $ver to ${versionFile.getName}")
        val versionSetting = if (useGlobalVersion) ThisBuild / version := ver else version := ver
        val newState       = extracted.appendWithSession(Seq(versionSetting), ctx.state)
        ctx.copy(state = newState)
      }
  }
}
