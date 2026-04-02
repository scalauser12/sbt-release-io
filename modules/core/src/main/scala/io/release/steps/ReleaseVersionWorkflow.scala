package io.release.steps

import cats.effect.IO
import io.release.ReleaseContext
import io.release.ReleaseIO.*
import io.release.internal.DecisionResolver
import io.release.VcsOps
import io.release.internal.ReleaseLogPrefixes
import io.release.internal.SbtRuntime
import io.release.internal.VersionPlan
import io.release.steps.StepHelpers.*
import sbt.Keys.*
import sbt.{internal as _, *}

import java.nio.charset.StandardCharsets
import java.nio.file.Files

/** Shared internal workflow for version resolution, version-file writes, and commit state
  * updates.
  *
  * [[VersionSteps]] keeps the public step definitions and names; this helper owns the underlying
  * version mechanics.
  */
private[release] object ReleaseVersionWorkflow {

  final case class ResolvedSettings(
      versionFile: File,
      readVersion: File => IO[String],
      versionFileContents: (File, String) => IO[String],
      useGlobalVersion: Boolean
  )

  final case class ResolvedVersions(
      versionFile: File,
      currentVersion: String,
      releaseVersion: String,
      nextVersion: String
  )

  private final case class InquireData(
      state: State,
      currentVersion: String,
      suggestedRelease: String,
      nextVersionFn: String => String,
      releaseVersionArg: Option[String],
      nextVersionArg: Option[String],
      useDefaults: Boolean
  )

  private[steps] def resolveCurrentSettings(state: State): ResolvedSettings =
    ResolvedSettings(
      versionFile = SbtRuntime.getSetting(state, releaseIOVersioningFile),
      readVersion = SbtRuntime.getSetting(state, releaseIOVersioningReadVersion),
      versionFileContents = SbtRuntime.getSetting(state, releaseIOVersioningFileContents),
      useGlobalVersion = SbtRuntime.getSetting(state, releaseIOVersioningUseGlobal)
    )

  private[steps] def sessionSettings(state: State): Seq[Setting[?]] = {
    val settings = resolveCurrentSettings(state)

    sessionSettings(
      VersionPlan(
        versionFile = settings.versionFile,
        readVersion = settings.readVersion,
        versionFileContents = settings.versionFileContents,
        releaseVersionOverride = None,
        nextVersionOverride = None,
        useGlobalVersion = settings.useGlobalVersion
      )
    )
  }

  private[steps] def sessionSettings(versionPlan: VersionPlan): Seq[Setting[?]] =
    Seq(
      releaseIOVersioningFile         := versionPlan.versionFile,
      releaseIOVersioningReadVersion  := versionPlan.readVersion,
      releaseIOVersioningFileContents := versionPlan.versionFileContents,
      releaseIOVersioningUseGlobal    := versionPlan.useGlobalVersion
    )

  def validateInquireVersions(ctx: ReleaseContext): IO[Unit] =
    IO.blocking(resolveVersionPlan(ctx).versionFile).flatMap(ensureVersionFileExists)

  def inquireVersions(ctx: ReleaseContext): IO[ReleaseContext] =
    resolveVersions(ctx, allowPrompts = true).flatMap { case (updatedCtx, resolved) =>
      IO.blocking {
        updatedCtx.state.log.info(
          s"${ReleaseLogPrefixes.Core} Current version : ${resolved.currentVersion}"
        )
        updatedCtx.state.log.info(
          s"${ReleaseLogPrefixes.Core} Release version : ${resolved.releaseVersion}"
        )
        updatedCtx.state.log.info(
          s"${ReleaseLogPrefixes.Core} Next version    : ${resolved.nextVersion}"
        )

        updatedCtx.withVersions(resolved.releaseVersion, resolved.nextVersion)
      }
    }

  def writeReleaseVersion(ctx: ReleaseContext): IO[ReleaseContext] =
    requireVersions(ctx) { case (releaseVersion, _) =>
      writeVersion(ctx, releaseVersion)
    }

  def writeNextVersion(ctx: ReleaseContext): IO[ReleaseContext] =
    requireVersions(ctx) { case (_, nextVersion) =>
      writeVersion(ctx, nextVersion)
    }

  def commitReleaseVersion(ctx: ReleaseContext): IO[ReleaseContext] =
    requireVersions(ctx) { case (releaseVersion, _) =>
      for {
        versionPlan             <- IO.blocking(resolveVersionPlan(ctx))
        commitResult            <- commitVersionNative(
                                     ctx,
                                     "commit-release-version",
                                     releaseIOVcsReleaseCommitMessage,
                                     versionPlan.versionFile
                                   )
        (resultCtx, currentHash) = commitResult
        finalCtx                <- IO.blocking {
                                     val newState = SbtRuntime.appendWithSession(
                                       resultCtx.state,
                                       sessionSettings(versionPlan) ++
                                         Seq(releaseIOInternalReleaseHash := Some(currentHash)) ++
                                         versionValueSettings(versionPlan, releaseVersion)
                                     )
                                     resultCtx.withState(newState)
                                   }
      } yield finalCtx
    }

  def commitNextVersion(ctx: ReleaseContext): IO[ReleaseContext] =
    requireVersions(ctx) { case (_, nextVersion) =>
      for {
        versionPlan   <- IO.blocking(resolveVersionPlan(ctx))
        commitResult  <-
          commitVersionNative(
            ctx,
            "commit-next-version",
            releaseIOVcsNextCommitMessage,
            versionPlan.versionFile
          )
        (resultCtx, _) = commitResult
        finalCtx      <- IO.blocking {
                           val newState = SbtRuntime.appendWithSession(
                             resultCtx.state,
                             sessionSettings(versionPlan) ++
                               versionValueSettings(versionPlan, nextVersion)
                           )
                           resultCtx.withState(newState)
                         }
      } yield finalCtx
    }

  private[release] def resolveVersions(
      ctx: ReleaseContext,
      allowPrompts: Boolean
  ): IO[(ReleaseContext, ResolvedVersions)] =
    for {
      versionPlan     <- IO.blocking(resolveVersionPlan(ctx))
      _               <- ensureVersionFileExists(versionPlan.versionFile)
      currentVer      <- versionPlan.readVersion(versionPlan.versionFile)
      releaseTaskData <-
        runTaskChecked(ctx.state, releaseIOVersioningReleaseVersion, "inquire-versions")
      (s1, releaseFn)  = releaseTaskData
      nextTaskData    <- runTaskChecked(s1, releaseIOVersioningNextVersion, "inquire-versions")
      (s2, nextFn)     = nextTaskData
      updatedCtx       = ctx.withState(s2)
      data             = InquireData(
                           state = s2,
                           currentVersion = currentVer,
                           suggestedRelease = releaseFn(currentVer),
                           nextVersionFn = nextFn,
                           releaseVersionArg = versionPlan.releaseVersionOverride,
                           nextVersionArg = versionPlan.nextVersionOverride,
                           useDefaults = useDefaults(updatedCtx)
                         )
      releaseData     <- resolveReleaseVersion(updatedCtx, data, allowPrompts)
      nextData        <- resolveNextVersion(releaseData._1, data, releaseData._2, allowPrompts)
    } yield (
      nextData._1,
      ResolvedVersions(
        versionFile = versionPlan.versionFile,
        currentVersion = currentVer,
        releaseVersion = releaseData._2,
        nextVersion = nextData._2
      )
    )

  private[release] def resolveVersionPlan(
      ctx: ReleaseContext,
      resolveSettings: State => ResolvedSettings = resolveCurrentSettings
  ): VersionPlan = {
    val settings = resolveSettings(ctx.state)
    val plan     = ctx.executionState.map(_.plan)

    VersionPlan(
      versionFile = settings.versionFile,
      readVersion = settings.readVersion,
      versionFileContents = settings.versionFileContents,
      releaseVersionOverride = plan.flatMap(_.releaseVersionOverride),
      nextVersionOverride = plan.flatMap(_.nextVersionOverride),
      useGlobalVersion = settings.useGlobalVersion
    )
  }

  private def resolveReleaseVersion(
      ctx: ReleaseContext,
      data: InquireData,
      allowPrompts: Boolean
  ): IO[(ReleaseContext, String)] =
    DecisionResolver.resolveVersionInput(
      ctx,
      override_ = data.releaseVersionArg,
      suggested = data.suggestedRelease,
      prompt = s"Release version [${data.suggestedRelease}] : ",
      promptContext = "Release version",
      allowPrompts = allowPrompts,
      beforePrompt = IO.blocking(data.state.log.info("Press enter to use the default value"))
    )

  private def resolveNextVersion(
      ctx: ReleaseContext,
      data: InquireData,
      releaseVersion: String,
      allowPrompts: Boolean
  ): IO[(ReleaseContext, String)] =
    IO(data.nextVersionFn(releaseVersion)).flatMap { suggestedNext =>
      DecisionResolver.resolveVersionInput(
        ctx,
        override_ = data.nextVersionArg,
        suggested = suggestedNext,
        prompt = s"Next version [$suggestedNext] : ",
        promptContext = "Next version",
        allowPrompts = allowPrompts
      )
    }

  private def ensureVersionFileExists(versionFile: File): IO[Unit] =
    IO.blocking(versionFile.exists()).flatMap { exists =>
      if (exists) IO.unit
      else
        IO.raiseError(
          new IllegalStateException(
            s"Version file not found: ${versionFile.getPath}. " +
              "Create it with contents like `version := \"0.1.0-SNAPSHOT\"`, " +
              "or configure `releaseIOVersioningFile`, `releaseIOVersioningReadVersion`, and " +
              "`releaseIOVersioningFileContents`. See `releaseIO help` for setup details."
          )
        )
    }

  private def commitVersionNative(
      ctx: ReleaseContext,
      actionName: String,
      commitMessageKey: TaskKey[String],
      versionFile: File
  ): IO[(ReleaseContext, String)] =
    required(ctx.vcs, "VCS not initialized. Ensure initializeVcs runs before this step.") { vcs =>
      IO.blocking {
        val sign    = SbtRuntime.getSetting(ctx.state, releaseIOVcsSign)
        val signOff = SbtRuntime.getSetting(ctx.state, releaseIOVcsSignOff)
        (sign, signOff)
      }.flatMap { case (sign, signOff) =>
        VcsOps.relativizeToBase(vcs, versionFile).flatMap { relativePath =>
          for {
            status <- VcsOps.trackedStatus(vcs)
            result <- if (status.nonEmpty) {
                        for {
                          commitData        <- runTaskChecked(ctx.state, commitMessageKey, actionName)
                          (commitState, msg) = commitData
                          result            <- IO.uncancelable { _ =>
                                                 // Keep staging and commit atomic with respect to
                                                 // cancellation so we do not leave staged-but-
                                                 // uncommitted changes behind.
                                                 for {
                                                   _    <- vcs.add(relativePath)
                                                   _    <- vcs.commit(msg, sign, signOff)
                                                   hash <- vcs.currentHash
                                                 } yield (ctx.withState(commitState), hash)
                                               }
                        } yield result
                      } else {
                        vcs.currentHash.map(hash => (ctx, hash))
                      }
          } yield result
        }
      }
    }

  private def writeVersion(ctx: ReleaseContext, versionValue: String): IO[ReleaseContext] =
    for {
      versionPlan <- IO.blocking(resolveVersionPlan(ctx))
      contents    <- versionPlan.versionFileContents(versionPlan.versionFile, versionValue)
      _           <- IO.blocking {
                       Files.write(
                         versionPlan.versionFile.toPath,
                         contents.getBytes(StandardCharsets.UTF_8)
                       )
                       ctx.state.log.info(
                         s"${ReleaseLogPrefixes.Core} Wrote version $versionValue to ${versionPlan.versionFile.getName}"
                       )
                     }
      result      <- IO.blocking {
                       val newState = SbtRuntime.appendWithSession(
                         ctx.state,
                         sessionSettings(versionPlan) ++
                           versionValueSettings(versionPlan, versionValue)
                       )
                       ctx.withState(newState)
                     }
    } yield result

  private def versionValueSettings(
      versionPlan: VersionPlan,
      versionValue: String
  ): Seq[Setting[?]] =
    if (versionPlan.useGlobalVersion)
      Seq(ThisBuild / version := versionValue, version := versionValue)
    else Seq(version          := versionValue)
}
