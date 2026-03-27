package io.release.steps

import cats.effect.IO
import io.release.ReleaseContext
import io.release.ReleaseIO.releaseIOTagComment
import io.release.ReleaseIO.releaseIOTagName
import io.release.ReleaseIO.releaseIOReadVersion
import io.release.ReleaseIO.releaseIOVcsSign
import io.release.ReleaseIO.releaseIOUseGlobalVersion
import io.release.ReleaseIO.releaseIOVersionFile
import io.release.ReleaseIO.releaseIOVersionFileContents
import io.release.ReleaseStepIO
import io.release.VcsOps
import io.release.internal.ReleaseLogPrefixes
import io.release.internal.SbtRuntime
import io.release.internal.TagPlan
import io.release.internal.VersionPlan
import io.release.steps.StepHelpers.*
import io.release.vcs.TagConflictResolver
import io.release.vcs.Vcs
import sbt.Keys.*
import sbt.Package.ManifestAttributes
import sbt.{internal as _, *}

/** VCS-related release steps: initialize, check, tag, push. */
private[release] object VcsSteps {

  private val DefaultCommandName = "releaseIO"

  private[release] final case class PreflightTagOutcome(
      tagName: String,
      status: String
  )

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
        params <- IO.blocking(resolveTagPlan(ctx))
        result <- resolveTag(vcs, params, ctx.withState(params.state))
      } yield result
    }
  }

  private def versionSessionSettings(state: State): Seq[Setting[?]] = {
    val extracted        = SbtRuntime.extracted(state)
    val maybeVersionPlan = for {
      versionFile         <- extracted.getOpt(releaseIOVersionFile)
      readVersion         <- extracted.getOpt(releaseIOReadVersion)
      versionFileContents <- extracted.getOpt(releaseIOVersionFileContents)
      useGlobalVersion    <- extracted.getOpt(releaseIOUseGlobalVersion)
    } yield VersionPlan(
      versionFile = versionFile,
      readVersion = readVersion,
      versionFileContents = versionFileContents,
      releaseVersionOverride = None,
      nextVersionOverride = None,
      useGlobalVersion = useGlobalVersion
    )

    maybeVersionPlan.fold(Seq.empty[Setting[?]])(VersionSteps.sessionSettings)
  }

  private def resolveTagPlan(ctx: ReleaseContext): TagPlan = {
    val versionSettings  = versionSessionSettings(ctx.state)
    val (s1, tagName)    = SbtRuntime.runTask(ctx.state, releaseIOTagName)
    val (s2, tagComment) = SbtRuntime.runTask(s1, releaseIOTagComment)
    TagPlan(
      state = s2,
      tagName = tagName,
      tagComment = tagComment,
      sign = SbtRuntime.getSetting(s2, releaseIOVcsSign),
      defaultAnswer = ctx.executionState.flatMap(_.plan.tagDefault),
      versionSessionSettings = versionSettings
    )
  }

  private[release] def preflightTag(ctx: ReleaseContext): IO[PreflightTagOutcome] =
    preparePreflightContext(ctx).flatMap { preflightCtx =>
      IO.blocking(resolveTagPlan(preflightCtx)).flatMap { params =>
        val detectedVcs = preflightCtx.vcs.fold(VcsOps.detectVcs(preflightCtx.state))(IO.pure)
        detectedVcs.flatMap(vcs => resolveTagPreflight(vcs, params, preflightCtx))
      }
    }

  private def preparePreflightContext(ctx: ReleaseContext): IO[ReleaseContext] =
    ctx.releaseVersion match {
      case None             => IO.pure(ctx)
      case Some(releaseVer) =>
        IO.blocking {
          val versionSettings =
            // Tag-name resolution only needs the release version visible through sbt settings.
            // Set both scopes so preflight works even in minimal/custom test states that do not
            // define the full version-file configuration.
            Seq(ThisBuild / version := releaseVer, version := releaseVer)
          val preflightState  = SbtRuntime.appendWithSession(
            ctx.state,
            versionSettings
          )

          ctx.withState(preflightState)
        }
    }

  private def applyTagToState(
      ctx: ReleaseContext,
      params: TagPlan,
      tagName: String
  ): ReleaseContext =
    ctx.withState(
      SbtRuntime.appendWithSession(
        ctx.state,
        params.versionSessionSettings ++
          Seq(packageOptions += ManifestAttributes("Vcs-Release-Tag" -> tagName))
      )
    )

  private def resolveTag(
      vcs: Vcs,
      params: TagPlan,
      ctx: ReleaseContext
  ): IO[ReleaseContext] =
    TagConflictResolver
      .resolveConflict(
        vcs,
        TagConflictResolver.TagParams(
          tagName = params.tagName,
          tagComment = params.tagComment,
          sign = params.sign,
          interactive = ctx.interactive,
          useDefaults = ctx.useDefaults,
          defaultAnswer = params.defaultAnswer,
          logPrefix = ReleaseLogPrefixes.Core,
          label = ""
        ),
        ctx.state
      )
      .map(result => applyTagToState(ctx, params, result.tagName))

  private def resolveTagPreflight(
      vcs: Vcs,
      params: TagPlan,
      ctx: ReleaseContext
  ): IO[PreflightTagOutcome] = {
    val commandName = ctx.executionState.map(_.plan.commandName).getOrElse(DefaultCommandName)

    TagConflictResolver
      .preflightConflict(
        vcs,
        TagConflictResolver.PreflightParams(
          tagName = params.tagName,
          interactive = ctx.interactive,
          useDefaults = ctx.useDefaults,
          defaultAnswer = params.defaultAnswer,
          commandName = commandName,
          label = ""
        )
      )
      .map(o => PreflightTagOutcome(o.tagName, o.status))
  }

  // Validation checks upstream config (local, fast). Remote reachability (git ls-remote) is
  // deferred to execute to avoid blocking the validation phase on a network call.
  val pushChanges: ReleaseStepIO = ReleaseStepIO(
    name = "push-changes",
    validate = ctx =>
      required(ctx.vcs, "VCS not initialized. Ensure initializeVcs runs before this step.") { vcs =>
        VcsOps.validatePushReadiness(ctx.state, ctx.interactive, ctx.useDefaults, vcs)
      },
    execute = ctx =>
      requireVcs(ctx) { vcs =>
        VcsOps.interactivePushAfterRemote(
          ctx.state,
          ctx.interactive,
          ctx.useDefaults,
          vcs,
          remoteCheckLog =
            Some(r => ctx.state.log.info(s"${ReleaseLogPrefixes.Core} Checking remote [$r] ..."))
        )(
          doPush = vcs.pushChanges.as(ctx),
          onDeclinePush = IO
            .blocking(
              ctx.state.log.warn(
                s"${ReleaseLogPrefixes.Core} Remember to push the changes yourself!"
              )
            )
            .as(ctx)
        )
      }
  )

}
