package io.release.steps

import cats.Monad
import cats.effect.IO
import io.release.internal.{CoreReleasePlan, SbtRuntime, TagPlan}
import io.release.{ReleaseContext, ReleaseStepIO, VcsOps}
import sbt.{internal => _, *}
import _root_.io.release.ReleaseIO.{releaseIOTagComment, releaseIOTagName, releaseIOVcsSign}
import _root_.io.release.steps.StepHelpers.*
import sbt.Keys.*
import sbt.Package.ManifestAttributes

import _root_.io.release.vcs.Vcs

/** VCS-related release steps: initialize, check, tag, push. */
private[release] object VcsSteps {

  val initializeVcs: ReleaseStepIO = ReleaseStepIO.io("initialize-vcs") { ctx =>
    VcsOps.detectAndInit(ctx)
  }

  val checkCleanWorkingDir: ReleaseStepIO = ReleaseStepIO(
    name = "check-clean-working-dir",
    execute = ctx => IO.pure(ctx),
    validate = validateCleanWorkingDir(_, logStartHash = true)
  )

  def validateCleanWorkingDir(
      ctx: ReleaseContext,
      logStartHash: Boolean
  ): IO[Unit] = {
    val checkIO = ctx.vcs match {
      case Some(vcs) => VcsOps.checkCleanWorkingDir(ctx.state, vcs)
      case None      => VcsOps.checkCleanWorkingDir(ctx.state)
    }
    checkIO.flatMap { result =>
      IO.blocking {
        if (logStartHash)
          ctx.state.log.info(
            s"[release-io] Starting release process off commit: ${result.currentHash}"
          )
        ()
      }
    }
  }

  val tagRelease: ReleaseStepIO = ReleaseStepIO.io("tag-release") { ctx =>
    requireVcs(ctx) { vcs =>
      for {
        params <- IO.blocking(resolveTagPlan(ctx.state))
        result <- resolveTag(vcs, params, ctx.copy(state = params.state))
      } yield result
    }
  }

  private def resolveTagPlan(state: sbt.State): TagPlan = {
    val (s1, tagName)    = SbtRuntime.runTask(state, releaseIOTagName)
    val (s2, tagComment) = SbtRuntime.runTask(s1, releaseIOTagComment)
    TagPlan(
      state = s2,
      tagName = tagName,
      tagComment = tagComment,
      sign = SbtRuntime.getSetting(s2, releaseIOVcsSign),
      defaultAnswer = CoreReleasePlan.current(s2).flatMap(_.tagDefault)
    )
  }

  private def resolveTag(
      vcs: Vcs,
      params: TagPlan,
      ctx: ReleaseContext
  ): IO[ReleaseContext] =
    Monad[IO].tailRecM(params) { currentParams =>
      val TagPlan(_, tagName, tagComment, sign, defaultAnswer) = currentParams
      val useDefaults                                          = StepHelpers.useDefaults(currentParams.state)
      for {
        exists <- vcs.existsTag(tagName)
        result <- if (!exists)
                    (vcs.tag(tagName, tagComment, sign) *>
                      IO.blocking {
                        val newState = SbtRuntime.appendWithSession(
                          ctx.state,
                          VersionSteps.sessionSettings(ctx.state) ++
                            Seq(packageOptions += ManifestAttributes("Vcs-Release-Tag" -> tagName))
                        )
                        ctx.copy(state = newState)
                      }).map(Right(_))
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
                            VersionSteps.sessionSettings(ctx.state) ++
                              Seq(
                                packageOptions += ManifestAttributes("Vcs-Release-Tag" -> tagName)
                              )
                          )
                          Right(ctx.copy(state = newState))
                        }
                      case "o" | "O"      =>
                        IO.blocking(
                          ctx.state.log.warn(
                            s"[release-io] Tag [$tagName] already exists. Overwriting."
                          )
                        ) *>
                          vcs.tag(tagName, tagComment, sign, force = true) *>
                          IO.blocking {
                            val newState = SbtRuntime.appendWithSession(
                              ctx.state,
                              VersionSteps.sessionSettings(ctx.state) ++
                                Seq(
                                  packageOptions += ManifestAttributes("Vcs-Release-Tag" -> tagName)
                                )
                            )
                            Right(ctx.copy(state = newState))
                          }
                      case newTagName     =>
                        IO.blocking(
                          ctx.state.log.info(
                            s"[release-io] Tag [$tagName] exists. Trying tag [$newTagName]."
                          )
                        ).as(Left(currentParams.copy(tagName = newTagName, defaultAnswer = None)))
                    }
                  }
      } yield result
    }

  val pushChanges: ReleaseStepIO = ReleaseStepIO(
    name = "push-changes",
    validate = ctx =>
      required(ctx.vcs, "VCS not initialized. Ensure initializeVcs runs before this step.") { vcs =>
        for {
          hasUp  <- vcs.hasUpstream
          _      <-
            if (hasUp) IO.unit
            else
              vcs.currentBranch.flatMap { branch =>
                IO.raiseError(
                  new IllegalStateException(
                    s"[release-io] No tracking branch configured for branch '$branch'. " +
                      "Set up a remote tracking branch or remove pushChanges from the release process."
                  )
                )
              }
          // Best-effort check using local tracking refs (no fetch).
          // If tracking refs are missing (e.g. remote never fetched), treat as not behind.
          behind <- vcs.isBehindRemote.handleError(_ => false)
          _      <-
            if (!behind) IO.unit
            else
              confirmContinue(
                ctx,
                prompt =
                  "The upstream branch has unmerged commits. A subsequent push may fail! Continue (y/n)? [n] ",
                defaultYes = false,
                abortMessage = "Merge the upstream commits and run release again."
              )
        } yield ()
      },
    execute = ctx =>
      requireVcs(ctx) { vcs =>
        validatePushRemote(ctx, vcs) *>
          (if (!ctx.interactive)
             vcs.pushChanges.as(ctx)
           else {
             val decisionIO =
               if (useDefaults(ctx.state)) IO.pure(true)
               else
                 askYesNo(
                   prompt = "Push changes to the remote repository (y/n)? [y] ",
                   defaultYes = true
                 )

             decisionIO.flatMap {
               case true  => vcs.pushChanges.as(ctx)
               case false =>
                 IO.blocking(
                   ctx.state.log.warn(
                     "[release-io] Remember to push the changes yourself!"
                   )
                 ).as(ctx)
             }
           })
      }
  )

  private def validatePushRemote(ctx: ReleaseContext, vcs: Vcs): IO[Unit] =
    for {
      remote         <- vcs.trackingRemote
      _              <- IO.blocking(ctx.state.log.info(s"[release-io] Checking remote [$remote] ..."))
      remoteExitCode <- vcs.checkRemote(remote)
      _              <-
        if (remoteExitCode == 0) IO.unit
        else
          confirmContinue(
            ctx,
            prompt = "Error while checking remote. Still continue (y/n)? [n] ",
            defaultYes = false,
            abortMessage = "Aborting the release due to remote check failure."
          )
    } yield ()

}
