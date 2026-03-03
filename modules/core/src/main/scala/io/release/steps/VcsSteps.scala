package io.release.steps

import cats.effect.IO
import io.release.steps.StepHelpers.*
import io.release.{ReleaseContext, ReleaseKeys, ReleaseStepIO}
import sbt.Keys.*
import sbt.Package.ManifestAttributes
import sbt.Project.extract
import sbtrelease.ReleasePlugin.autoImport.*
import sbtrelease.Vcs

/** VCS-related release steps: initialize, check, tag, push. */
private[release] object VcsSteps {

  val initializeVcs: ReleaseStepIO = ReleaseStepIO.io("initialize-vcs") { ctx =>
    val baseDir = extract(ctx.state).get(thisProject).base
    IO.blocking(Vcs.detect(baseDir)).flatMap {
      case Some(sbtVcs) =>
        IO.blocking {
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
    check = checkCleanWorkingDirInternal(_, logStartHash = true)
  )

  def checkCleanWorkingDirInternal(
      ctx: ReleaseContext,
      logStartHash: Boolean
  ): IO[ReleaseContext] = {
    val extracted       = extract(ctx.state)
    val base            = extracted.get(thisProject).base
    val ignoreUntracked = extracted.get(releaseIgnoreUntrackedFiles)

    for {
      maybeVcs                          <- IO.blocking(Vcs.detect(base))
      vcs                               <- IO.fromOption(maybeVcs)(
                                             new RuntimeException(
                                               s"No VCS detected at ${base.getAbsolutePath}"
                                             )
                                           )
      vcsInfo                           <- IO.blocking(
                                             (vcs.modifiedFiles, vcs.untrackedFiles, vcs.currentHash)
                                           )
      (modified, untracked, currentHash) = vcsInfo
      result                            <- {
        if (modified.nonEmpty)
          IO.raiseError[ReleaseContext](
            new RuntimeException(
              s"""Aborting release: unstaged modified files
                 |
                 |Modified files:
                 |
                 |${modified.map(" - " + _).mkString("\n")}
                 |""".stripMargin
            )
          )
        else if (untracked.nonEmpty && !ignoreUntracked)
          IO.raiseError[ReleaseContext](
            new RuntimeException(
              s"""Aborting release: untracked files. Remove them or specify 'releaseIgnoreUntrackedFiles := true' in settings
                 |
                 |Untracked files:
                 |
                 |${untracked.map(" - " + _).mkString("\n")}
                 |""".stripMargin
            )
          )
        else
          IO.blocking {
            if (logStartHash)
              ctx.state.log.info(
                s"[release-io] Starting release process off commit: $currentHash"
              )
            ctx.withVcs(vcs)
          }
      }
    } yield result
  }

  val tagRelease: ReleaseStepIO = ReleaseStepIO.io("tag-release") { ctx =>
    requireVcs(ctx) { vcs =>
      for {
        setup               <- IO.blocking {
                                 val extracted        = extract(ctx.state)
                                 val (s1, tagName)    = extracted.runTask(releaseTagName, ctx.state)
                                 val (s2, tagComment) = extracted.runTask(releaseTagComment, s1)
                                 val sign             = extracted.get(releaseVcsSign)
                                 val defaultAnswer    = s2.get(ReleaseKeys.tagDefault).flatten
                                 val useDefaults      =
                                   s2.get(ReleaseKeys.useDefaults).getOrElse(false)
                                 TagParams(tagName, tagComment, sign, defaultAnswer, useDefaults) ->
                                   ctx.copy(state = s2)
                               }
        (params, updatedCtx) = setup
        result              <- resolveTag(vcs, params, updatedCtx)
      } yield result
    }
  }

  private def resolveTag(
      vcs: Vcs,
      params: TagParams,
      ctx: ReleaseContext
  ): IO[ReleaseContext] = {
    val TagParams(tagName, tagComment, sign, defaultAnswer, useDefaults) = params
    for {
      exists <- IO.blocking(vcs.existsTag(tagName))
      result <- if (!exists)
                  runProcess(vcs.tag(tagName, tagComment, sign = sign), s"vcs tag '$tagName'") *>
                    IO.blocking {
                      val newState = extract(ctx.state).appendWithSession(
                        Seq(packageOptions += ManifestAttributes("Vcs-Release-Tag" -> tagName)),
                        ctx.state
                      )
                      ctx.copy(state = newState)
                    }
                else {
                  val effectiveAnswer: IO[String] = defaultAnswer match {
                    case Some(ans)                => IO.pure(ans)
                    case None if useDefaults      =>
                      IO.blocking(
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
                      IO.blocking(
                        ctx.state.log
                          .warn(
                            s"[release-io] Tag [$tagName] already exists. Keeping existing tag."
                          )
                      ).as(ctx)
                    case "o" | "O"      =>
                      IO.blocking(
                        ctx.state.log
                          .warn(s"[release-io] Tag [$tagName] already exists. Overwriting.")
                      ) *>
                        runProcess(
                          vcs.tag(tagName, tagComment, sign = sign),
                          s"vcs tag '$tagName'"
                        ) *>
                        IO.blocking {
                          val newState = extract(ctx.state).appendWithSession(
                            Seq(
                              packageOptions += ManifestAttributes("Vcs-Release-Tag" -> tagName)
                            ),
                            ctx.state
                          )
                          ctx.copy(state = newState)
                        }
                    case newTagName     =>
                      IO.blocking(
                        ctx.state.log
                          .info(s"[release-io] Tag [$tagName] exists. Trying tag [$newTagName].")
                      ) *>
                        resolveTag(
                          vcs,
                          params
                            .copy(tagName = newTagName, defaultAnswer = None, useDefaults = false),
                          ctx
                        )
                  }
                }
    } yield result
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
        for {
          hasUp  <- IO.blocking(vcs.hasUpstream)
          result <- if (!hasUp)
                      for {
                        branch <- IO.blocking(vcs.currentBranch)
                        r      <-
                          IO.blocking(
                            ctx.state.log.info(
                              s"[release-io] Changes were NOT pushed, because no upstream branch is configured for branch '$branch'."
                            )
                          ).as(ctx)
                      } yield r
                    else if (!ctx.interactive)
                      runProcess(vcs.pushChanges, "vcs push").as(ctx)
                    else {
                      val useDefaults = ctx.state.get(ReleaseKeys.useDefaults).getOrElse(false)
                      val decisionIO  =
                        if (useDefaults) IO.pure(true)
                        else
                          askYesNo(
                            prompt = "Push changes to the remote repository (y/n)? [y] ",
                            defaultYes = true
                          )

                      decisionIO.flatMap {
                        case true  => runProcess(vcs.pushChanges, "vcs push").as(ctx)
                        case false =>
                          IO.blocking(
                            ctx.state.log.warn(
                              "[release-io] Remember to push the changes yourself!"
                            )
                          ).as(ctx)
                      }
                    }
        } yield result
      }
  )

  private final case class TagParams(
      tagName: String,
      tagComment: String,
      sign: Boolean,
      defaultAnswer: Option[String],
      useDefaults: Boolean
  )
}
