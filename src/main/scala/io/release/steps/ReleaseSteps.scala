package io.release.steps

import cats.effect.IO
import scala.sys.process.*
import io.release.{ReleaseContext, ReleaseKeys, ReleaseStepIO}
import sbt.*
import sbt.Keys.*
import sbt.Project.extract
import sbt.Package.ManifestAttributes
import sbtrelease.ReleasePlugin.autoImport.*
import sbtrelease.ReleaseStateTransformations.{
  runClean => upstreamRunClean,
  runTest => upstreamRunTest
}
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

  val checkCleanWorkingDir: ReleaseStepIO = ReleaseStepIO(
    name = "check-clean-working-dir",
    action = ctx => IO.pure(ctx),
    check = checkCleanWorkingDirImpl
  )

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
            .mkString(
              "\n"
            )
          val msg     = s"Snapshot dependencies found:\n$depList"

          if (!ctx.interactive) {
            IO.raiseError(new RuntimeException(msg))
          } else {
            IO(ctx.state.log.warn(msg)) *>
              confirmContinue(
                ctx,
                prompt = "Do you want to continue (y/n)? [n] ",
                defaultYes = false,
                abortMessage = "Aborting release due to snapshot dependencies."
              ).as(ctx)
          }
        case Right(_)                     => IO.pure(ctx)
      },
    enableCrossBuild = true
  )

  val inquireVersions: ReleaseStepIO = ReleaseStepIO.io("inquire-versions") { ctx =>
    final case class InquireData(
        state: State,
        currentVersion: String,
        suggestedRelease: String,
        nextVersionFn: String => String,
        releaseVersionArg: Option[String],
        nextVersionArg: Option[String],
        useDefaults: Boolean
    )

    for {
      data         <- IO.blocking {
                        val extracted  = extract(ctx.state)
                        val currentVer = extracted.get(version)

                        // Use upstream sbt-release's version functions which respect releaseVersionBump.
                        val (s1, releaseFn) = extracted.runTask(releaseVersion, ctx.state)
                        val (s2, nextFn)    = extracted.runTask(releaseNextVersion, s1)

                        InquireData(
                          state = s2,
                          currentVersion = currentVer,
                          suggestedRelease = releaseFn(currentVer),
                          nextVersionFn = nextFn,
                          releaseVersionArg = s2.get(ReleaseKeys.commandLineReleaseVersion).flatten,
                          nextVersionArg = s2.get(ReleaseKeys.commandLineNextVersion).flatten,
                          useDefaults = s2.get(ReleaseKeys.useDefaults).getOrElse(false)
                        )
                      }
      releaseVer   <-
        data.releaseVersionArg match {
          case Some(v)                                      => IO.pure(v)
          case None if !ctx.interactive || data.useDefaults => IO.pure(data.suggestedRelease)
          case None                                         =>
            IO(data.state.log.info("Press enter to use the default value")) *>
              readVersion(
                prompt = s"Release version [${data.suggestedRelease}] : ",
                defaultVersion = data.suggestedRelease
              )
        }
      suggestedNext = data.nextVersionFn(releaseVer)
      nextVer      <-
        data.nextVersionArg match {
          case Some(v)                                      => IO.pure(v)
          case None if !ctx.interactive || data.useDefaults => IO.pure(suggestedNext)
          case None                                         =>
            readVersion(
              prompt = s"Next version [${suggestedNext}] : ",
              defaultVersion = suggestedNext
            )
        }
      updated      <- IO {
                        data.state.log.info(s"[release-io] Current version : ${data.currentVersion}")
                        data.state.log.info(s"[release-io] Release version : $releaseVer")
                        data.state.log.info(s"[release-io] Next version    : $nextVer")

                        val updatedState = data.state.put(ReleaseKeys.versions, (releaseVer, nextVer))
                        ctx.copy(state = updatedState).withVersions(releaseVer, nextVer)
                      }
    } yield updated
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

  val runClean: ReleaseStepIO = ReleaseStepIO(
    name = "run-clean",
    action = ctx =>
      IO.blocking {
        val extracted = extract(ctx.state)
        val ref       = extracted.get(thisProjectRef)
        val newState  = extracted.runAggregated(ref / (Global / clean), ctx.state)
        ctx.copy(state = newState)
      }
  )

  val setReleaseVersion: ReleaseStepIO =
    ReleaseStepIO.io("set-release-version") { ctx =>
      requireVersions(ctx) { case (releaseVer, _) =>
        writeVersion(ctx, releaseVer)
      }
    }

  val commitReleaseVersion: ReleaseStepIO = ReleaseStepIO(
    name = "commit-release-version",
    check = checkCleanWorkingDirForCommit,
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
          case Some(ans)                => IO.pure(ans)
          case None if useDefaults      =>
            IO(
              ctx.state.log.warn(
                s"[release-io] Tag [$tagName] already exists. Aborting (use-defaults mode)."
              )
            ).as("a")
          case None if !ctx.interactive =>
            IO.raiseError(
              new RuntimeException(
                s"Tag [$tagName] already exists. Aborting release in non-interactive mode."
              )
            )
          case None                     =>
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
          val allRefs   = extracted.currentRef +: extracted.currentProject.aggregate
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
    check = ctx =>
      requireVcs(ctx) { vcs =>
        for {
          hasUp          <- IO.blocking(vcs.hasUpstream)
          _              <-
            if (hasUp) IO.unit
            else
              IO.raiseError(
                new RuntimeException(
                  s"[release-io] No tracking branch configured for branch '${vcs.currentBranch}'. " +
                    "Set up a remote tracking branch or remove pushChanges from the release process."
                )
              )
          remoteExitCode <- IO.blocking {
                              ctx.state.log.info(
                                s"[release-io] Checking remote [${vcs.trackingRemote}] ..."
                              )
                              vcs.checkRemote(vcs.trackingRemote).!
                            }
          _              <-
            if (remoteExitCode == 0) IO.unit
            else
              confirmContinue(
                ctx,
                prompt = "Error while checking remote. Still continue (y/n)? [n] ",
                defaultYes = false,
                abortMessage = "Aborting the release due to remote check failure."
              )
          behindRemote   <- IO.blocking(vcs.isBehindRemote)
          _              <-
            if (!behindRemote) IO.unit
            else
              confirmContinue(
                ctx,
                prompt =
                  "The upstream branch has unmerged commits. A subsequent push may fail! Continue (y/n)? [n] ",
                defaultYes = false,
                abortMessage = "Merge the upstream commits and run release again."
              )
        } yield ctx
      },
    action = ctx =>
      requireVcs(ctx) { vcs =>
        if (!vcs.hasUpstream) {
          IO(
            ctx.state.log.info(
              s"[release-io] Changes were NOT pushed, because no upstream branch is configured for branch '${vcs.currentBranch}'."
            )
          ).as(ctx)
        } else if (!ctx.interactive) {
          IO.blocking { runProcess(vcs.pushChanges, "vcs push") }.as(ctx)
        } else {
          val useDefaults = ctx.state.get(ReleaseKeys.useDefaults).getOrElse(false)
          val decisionIO  =
            if (useDefaults) IO.pure(true)
            else
              askYesNo(
                prompt = "Push changes to the remote repository (y/n)? [y] ",
                defaultYes = true
              )

          decisionIO.flatMap {
            case true  => IO.blocking { runProcess(vcs.pushChanges, "vcs push") }.as(ctx)
            case false =>
              IO(
                ctx.state.log.warn("[release-io] Remember to push the changes yourself!")
              ).as(ctx)
          }
        }
      }
  )

  /** Default ordered sequence of all release steps using IO-native implementations.
    * These steps provide richer error handling and use the ReleaseContext-based VCS/version plumbing.
    */
  val defaults: Seq[ReleaseStepIO] = Seq(
    initializeVcs,
    checkCleanWorkingDir,
    checkSnapshotDependencies,
    inquireVersions,
    runClean,
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
      checkCleanWorkingDir,
      checkSnapshotDependencies,
      inquireVersions,
      upstreamRunClean,
      upstreamRunTest,
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

  private def askYesNo(prompt: String, defaultYes: Boolean): IO[Boolean] =
    IO.print(prompt) *>
      IO.readLine.map { raw =>
        Option(raw).map(_.trim.toLowerCase).getOrElse("") match {
          case ""          => defaultYes
          case "y" | "yes" => true
          case "n" | "no"  => false
          case _           => false
        }
      }

  private def confirmContinue(
      ctx: ReleaseContext,
      prompt: String,
      defaultYes: Boolean,
      abortMessage: String
  ): IO[Unit] = {
    val useDefaults = ctx.state.get(ReleaseKeys.useDefaults).getOrElse(false)
    if (!ctx.interactive)
      IO.raiseError(new RuntimeException(abortMessage))
    else {
      val decisionIO =
        if (useDefaults) IO.pure(defaultYes)
        else askYesNo(prompt, defaultYes = defaultYes)

      decisionIO.flatMap { continue =>
        if (continue) IO.unit
        else IO.raiseError(new RuntimeException(abortMessage))
      }
    }
  }

  private def readVersion(prompt: String, defaultVersion: String): IO[String] =
    IO.print(prompt) *>
      IO.readLine.flatMap { raw =>
        val input = Option(raw).map(_.trim).getOrElse("")
        if (input.isEmpty) IO.pure(defaultVersion)
        else {
          sbtrelease.Version(input).map(_.unapply) match {
            case Some(v) => IO.pure(v)
            case None    =>
              IO.raiseError(
                new RuntimeException(s"Invalid version format: '$input'")
              )
          }
        }
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

  private def checkCleanWorkingDirImpl(ctx: ReleaseContext): IO[ReleaseContext] =
    checkCleanWorkingDirInternal(ctx, logStartHash = true)

  private def checkCleanWorkingDirForCommit(ctx: ReleaseContext): IO[ReleaseContext] =
    checkCleanWorkingDirInternal(ctx, logStartHash = false)

  private def checkCleanWorkingDirInternal(
      ctx: ReleaseContext,
      logStartHash: Boolean
  ): IO[ReleaseContext] = {
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
          val modified  = vcs.modifiedFiles
          val untracked = vcs.untrackedFiles

          if (modified.nonEmpty) {
            throw new RuntimeException(
              s"""Aborting release: unstaged modified files
                 |
                 |Modified files:
                 |
                 |${modified.mkString(" - ", "\n", "")}
                 |""".stripMargin
            )
          }

          if (untracked.nonEmpty && !ignoreUntracked) {
            throw new RuntimeException(
              s"""Aborting release: untracked files. Remove them or specify 'releaseIgnoreUntrackedFiles := true' in settings
                 |
                 |Untracked files:
                 |
                 |${untracked.mkString(" - ", "\n", "")}
                 |""".stripMargin
            )
          }

          if (logStartHash)
            ctx.state.log.info(
              s"[release-io] Starting release process off commit: ${vcs.currentHash}"
            )
          ctx.withVcs(vcs)
        }
    }
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
          val statusOutput = {
            val sb   = new StringBuilder
            val code = vcs.status.!(ProcessLogger(line => sb.append(line).append('\n'), _ => ()))
            if (code != 0)
              throw new RuntimeException(s"vcs status failed with exit code $code")
            sb.toString.trim
          }
          val status       = statusOutput.linesIterator
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
