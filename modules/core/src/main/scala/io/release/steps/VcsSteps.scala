package io.release.steps

import _root_.io.release.ReleaseIO.{releaseIOTagComment, releaseIOTagName, releaseIOVcsSign}
import _root_.io.release.steps.StepHelpers.*
import _root_.io.release.vcs.Vcs
import cats.Monad
import cats.effect.IO
import io.release.internal.{CoreReleasePlan, SbtRuntime, TagPlan}
import io.release.{ReleaseContext, ReleaseStepIO, VcsOps}
import sbt.Keys.*
import sbt.Package.ManifestAttributes
import sbt.internal as _

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
        VcsOps.validatePushReadiness(ctx.state, ctx.interactive, vcs)
      },
    execute = ctx =>
      requireVcs(ctx) { vcs =>
        VcsOps.validatePushRemote(
          ctx.state,
          ctx.interactive,
          vcs,
          log = Some(r => ctx.state.log.info(s"[release-io] Checking remote [$r] ..."))
        ) *>
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

}
