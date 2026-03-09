package io.release.steps

import cats.effect.IO
import io.release.internal.{CoreTagResolver, CoreVersionResolver, GitRuntime, SbtRuntime, TagPlan}
import io.release.{ReleaseContext, ReleaseKeys, ReleaseStepIO}
import sbt.*
import _root_.io.release.steps.StepHelpers.*
import sbt.Keys.*
import sbt.Package.ManifestAttributes
import sbtrelease.ReleasePlugin.autoImport.*
import sbtrelease.Vcs

/** VCS-related release steps: initialize, check, tag, push. */
private[release] object VcsSteps {

  val initializeVcs: ReleaseStepIO = ReleaseStepIO.io("initialize-vcs") { ctx =>
    GitRuntime.detectAndInit(ctx)
  }

  val checkCleanWorkingDir: ReleaseStepIO = ReleaseStepIO(
    name = "check-clean-working-dir",
    execute = ctx => IO.pure(ctx),
    validate = validateCleanWorkingDir(_, logStartHash = true)
  )

  def validateCleanWorkingDir(
      ctx: ReleaseContext,
      logStartHash: Boolean
  ): IO[Unit] =
    GitRuntime.checkCleanWorkingDir(ctx.state).flatMap { result =>
      IO.blocking {
        if (logStartHash)
          ctx.state.log.info(
            s"[release-io] Starting release process off commit: ${result.currentHash}"
          )
        ()
      }
    }

  val tagRelease: ReleaseStepIO = ReleaseStepIO.io("tag-release") { ctx =>
    requireVcs(ctx) { vcs =>
      for {
        params <- IO.blocking(CoreTagResolver.resolve(ctx.state))
        result <- resolveTag(vcs, params, ctx.copy(state = params.state))
      } yield result
    }
  }

  private def resolveTag(
      vcs: Vcs,
      params: TagPlan,
      ctx: ReleaseContext
  ): IO[ReleaseContext] = {
    val TagPlan(_, tagName, tagComment, sign, defaultAnswer) = params
    val useDefaults                                          = CoreTagResolver.useDefaultsFor(params)
    for {
      exists <- IO.blocking(vcs.existsTag(tagName))
      result <- if (!exists)
                  GitRuntime.tag(vcs, tagName, tagComment, sign) *>
                    IO.blocking {
                      val newState = SbtRuntime.appendWithSession(
                        ctx.state,
                        CoreVersionResolver.sessionSettings(ctx.state) ++
                          Seq(packageOptions += ManifestAttributes("Vcs-Release-Tag" -> tagName))
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
                        new IllegalStateException(
                          s"Tag [$tagName] already exists. Aborting release in non-interactive mode."
                        )
                      )
                    case None                     =>
                      IO.print(
                        s"Tag [$tagName] exists! Overwrite, keep or abort or enter a new tag (o/k/a)? [a] "
                      ) *> IO.readLine
                  }

                  effectiveAnswer.flatMap {
                    case "a" | "A" | "" =>
                      IO.raiseError(
                        new IllegalStateException(
                          s"Tag [$tagName] already exists. Aborting release!"
                        )
                      )
                    case "k" | "K"      =>
                      IO.blocking {
                        ctx.state.log.warn(
                          s"[release-io] Tag [$tagName] already exists. Keeping existing tag."
                        )
                        val newState = SbtRuntime.appendWithSession(
                          ctx.state,
                          CoreVersionResolver.sessionSettings(ctx.state) ++
                            Seq(packageOptions += ManifestAttributes("Vcs-Release-Tag" -> tagName))
                        )
                        ctx.copy(state = newState)
                      }
                    case "o" | "O"      =>
                      IO.blocking(
                        ctx.state.log.warn(
                          s"[release-io] Tag [$tagName] already exists. Overwriting."
                        )
                      ) *>
                        GitRuntime.tag(vcs, tagName, tagComment, sign) *>
                        IO.blocking {
                          val newState = SbtRuntime.appendWithSession(
                            ctx.state,
                            CoreVersionResolver.sessionSettings(ctx.state) ++
                              Seq(
                                packageOptions += ManifestAttributes("Vcs-Release-Tag" -> tagName)
                              )
                          )
                          ctx.copy(state = newState)
                        }
                    case newTagName     =>
                      IO.blocking(
                        ctx.state.log.info(
                          s"[release-io] Tag [$tagName] exists. Trying tag [$newTagName]."
                        )
                      ) *>
                        resolveTag(
                          vcs,
                          params
                            .copy(tagName = newTagName, defaultAnswer = None),
                          ctx
                        )
                  }
                }
    } yield result
  }

  val pushChanges: ReleaseStepIO = ReleaseStepIO(
    name = "push-changes",
    validate = ctx =>
      required(ctx.vcs, "VCS not initialized. Ensure initializeVcs runs before this step.") { vcs =>
        for {
          hasUp <- IO.blocking(vcs.hasUpstream)
          _     <-
            if (hasUp) IO.unit
            else
              IO.blocking(vcs.currentBranch).flatMap { branch =>
                IO.raiseError(
                  new IllegalStateException(
                    s"[release-io] No tracking branch configured for branch '$branch'. " +
                      "Set up a remote tracking branch or remove pushChanges from the release process."
                  )
                )
              }
        } yield ()
      },
    execute = ctx =>
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
                    else
                      validatePushRemote(ctx, vcs) *>
                        (if (!ctx.interactive)
                           GitRuntime.pushChanges(vcs).as(ctx)
                         else {
                           val decisionIO =
                             if (useDefaults(ctx.state)) IO.pure(true)
                             else
                               askYesNo(
                                 prompt = "Push changes to the remote repository (y/n)? [y] ",
                                 defaultYes = true
                               )

                           decisionIO.flatMap {
                             case true  => GitRuntime.pushChanges(vcs).as(ctx)
                             case false =>
                               IO.blocking(
                                 ctx.state.log.warn(
                                   "[release-io] Remember to push the changes yourself!"
                                 )
                               ).as(ctx)
                           }
                         })
        } yield result
      }
  )

  private def validatePushRemote(ctx: ReleaseContext, vcs: Vcs): IO[Unit] =
    for {
      remote         <- IO.blocking(vcs.trackingRemote)
      remoteExitCode <- IO.blocking {
                          ctx.state.log.info(s"[release-io] Checking remote [$remote] ...")
                          vcs.checkRemote(remote).!
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
    } yield ()

}
