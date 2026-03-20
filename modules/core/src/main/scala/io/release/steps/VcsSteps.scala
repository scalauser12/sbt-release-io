package io.release.steps

import cats.effect.IO
import cats.syntax.flatMap.*
import io.release.ReleaseIO.{releaseIOTagComment, releaseIOTagName, releaseIOVcsSign}
import io.release.internal.{CoreReleasePlan, ReleaseLogPrefixes, SbtRuntime, TagPlan}
import io.release.steps.StepHelpers.*
import io.release.vcs.Vcs
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
            s"${ReleaseLogPrefixes.Core} Starting release process off commit: ${result.currentHash}"
          )
        ()
      }
    }
  }

  // No validation phase: the tag name depends on releaseIOTagName, which is resolved from the
  // release version set by inquireVersions.execute. At validation time, that version is not yet
  // available, so tag-exists checks can only run during execution.
  val tagRelease: ReleaseStepIO = ReleaseStepIO.io("tag-release") { ctx =>
    requireVcs(ctx) { vcs =>
      for {
        params <- IO.blocking(resolveTagPlan(ctx.state))
        result <- resolveTag(vcs, params, ctx.withState(params.state))
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

  private def applyTagToState(ctx: ReleaseContext, tagName: String): ReleaseContext =
    ctx.withState(
      SbtRuntime.appendWithSession(
        ctx.state,
        VersionSteps.sessionSettings(ctx.state) ++
          Seq(packageOptions += ManifestAttributes("Vcs-Release-Tag" -> tagName))
      )
    )

  private def resolveTag(
      vcs: Vcs,
      params: TagPlan,
      ctx: ReleaseContext
  ): IO[ReleaseContext] =
    params.tailRecM[IO, ReleaseContext] { currentParams =>
      val TagPlan(_, tagName, tagComment, sign, defaultAnswer) = currentParams
      val useDefaults                                          = StepHelpers.useDefaults(currentParams.state)
      for {
        exists <- vcs.existsTag(tagName)
        result <- if (!exists)
                    (vcs.tag(tagName, tagComment, sign) *>
                      IO.blocking(applyTagToState(ctx, tagName))).map(Right(_))
                  else {
                    val effectiveAnswer: IO[String] = defaultAnswer match {
                      case Some(ans)                => IO.pure(ans)
                      case None if useDefaults      =>
                        IO.blocking(
                          ctx.state.log.warn(
                            s"${ReleaseLogPrefixes.Core} Tag [$tagName] already exists. Aborting (use-defaults mode)."
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
                            s"${ReleaseLogPrefixes.Core} Tag [$tagName] already exists. Keeping existing tag."
                          )
                          Right(applyTagToState(ctx, tagName))
                        }
                      case "o" | "O"      =>
                        IO.blocking(
                          ctx.state.log.warn(
                            s"${ReleaseLogPrefixes.Core} Tag [$tagName] already exists. Overwriting."
                          )
                        ) *>
                          vcs.tag(tagName, tagComment, sign, force = true) *>
                          IO.blocking(Right(applyTagToState(ctx, tagName)))
                      case newTagName     =>
                        IO.blocking(
                          ctx.state.log.info(
                            s"${ReleaseLogPrefixes.Core} Tag [$tagName] exists. Trying tag [$newTagName]."
                          )
                        ).as(Left(currentParams.copy(tagName = newTagName, defaultAnswer = None)))
                    }
                  }
      } yield result
    }

  // Validation checks upstream config (local, fast). Remote reachability (git ls-remote) is
  // deferred to execute to avoid blocking the validation phase on a network call.
  val pushChanges: ReleaseStepIO = ReleaseStepIO(
    name = "push-changes",
    validate = ctx =>
      required(ctx.vcs, "VCS not initialized. Ensure initializeVcs runs before this step.") { vcs =>
        VcsOps.validatePushReadiness(ctx.state, ctx.interactive, vcs)
      },
    execute = ctx =>
      requireVcs(ctx) { vcs =>
        VcsOps.interactivePushAfterRemote(
          ctx.state,
          ctx.interactive,
          vcs,
          remoteCheckLog = Some(r =>
            ctx.state.log.info(s"${ReleaseLogPrefixes.Core} Checking remote [$r] ...")
          )
        )(
          doPush = vcs.pushChanges.as(ctx),
          onDeclinePush = IO.blocking(
            ctx.state.log.warn(
              s"${ReleaseLogPrefixes.Core} Remember to push the changes yourself!"
            )
          ).as(ctx)
        )
      }
  )

}
