package io.release.steps

import cats.effect.IO
import io.release.ReleaseContext
import io.release.ReleaseIO.*
import io.release.VcsOps
import io.release.internal.ReleaseLogPrefixes
import io.release.internal.SbtRuntime
import io.release.internal.VersionPlan
import io.release.steps.StepHelpers.*
import sbt.Keys.*
import sbt.Package.ManifestAttributes
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
      versionFile = SbtRuntime.getSetting(state, releaseIOVersionFile),
      readVersion = SbtRuntime.getSetting(state, releaseIOReadVersion),
      versionFileContents = SbtRuntime.getSetting(state, releaseIOVersionFileContents),
      useGlobalVersion = SbtRuntime.getSetting(state, releaseIOUseGlobalVersion)
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
      releaseIOVersionFile         := versionPlan.versionFile,
      releaseIOReadVersion         := versionPlan.readVersion,
      releaseIOVersionFileContents := versionPlan.versionFileContents,
      releaseIOUseGlobalVersion    := versionPlan.useGlobalVersion
    )

  def validateInquireVersions(ctx: ReleaseContext): IO[Unit] =
    IO.blocking(resolveVersionPlan(ctx).versionFile).flatMap(ensureVersionFileExists)

  def inquireVersions(ctx: ReleaseContext): IO[ReleaseContext] =
    resolveVersions(ctx, allowPrompts = true).flatMap { resolved =>
      IO.blocking {
        ctx.state.log.info(
          s"${ReleaseLogPrefixes.Core} Current version : ${resolved.currentVersion}"
        )
        ctx.state.log.info(
          s"${ReleaseLogPrefixes.Core} Release version : ${resolved.releaseVersion}"
        )
        ctx.state.log.info(
          s"${ReleaseLogPrefixes.Core} Next version    : ${resolved.nextVersion}"
        )

        ctx.withVersions(resolved.releaseVersion, resolved.nextVersion)
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
        commitResult            <- commitVersionNative(ctx, releaseIOCommitMessage, versionPlan.versionFile)
        (resultCtx, currentHash) = commitResult
        finalCtx                <- IO.blocking {
                                     val newState = SbtRuntime.appendWithSession(
                                       resultCtx.state,
                                       sessionSettings(versionPlan) ++
                                         Seq(
                                           packageOptions += ManifestAttributes(
                                             "Vcs-Release-Hash" -> currentHash
                                           )
                                         ) ++
                                         versionValueSettings(versionPlan, releaseVersion)
                                     )
                                     resultCtx.withState(newState)
                                   }
      } yield finalCtx
    }

  def commitNextVersion(ctx: ReleaseContext): IO[ReleaseContext] =
    requireVersions(ctx) { case (_, nextVersion) =>
      for {
        versionPlan  <- IO.blocking(resolveVersionPlan(ctx))
        commitResult <-
          commitVersionNative(ctx, releaseIONextCommitMessage, versionPlan.versionFile)
        finalCtx     <- IO.blocking {
                          val newState = SbtRuntime.appendWithSession(
                            commitResult._1.state,
                            sessionSettings(versionPlan) ++
                              versionValueSettings(versionPlan, nextVersion)
                          )
                          commitResult._1.withState(newState)
                        }
      } yield finalCtx
    }

  private[release] def resolveVersions(
      ctx: ReleaseContext,
      allowPrompts: Boolean
  ): IO[ResolvedVersions] =
    for {
      versionPlan <- IO.blocking(resolveVersionPlan(ctx))
      _           <- ensureVersionFileExists(versionPlan.versionFile)
      currentVer  <- versionPlan.readVersion(versionPlan.versionFile)
      data        <- IO.blocking {
                       val (s1, releaseFn) = SbtRuntime.runTask(ctx.state, releaseIOVersion)
                       val (_, nextFn)     = SbtRuntime.runTask(s1, releaseIONextVersion)

                       InquireData(
                         state = ctx.state,
                         currentVersion = currentVer,
                         suggestedRelease = releaseFn(currentVer),
                         nextVersionFn = nextFn,
                         releaseVersionArg = versionPlan.releaseVersionOverride,
                         nextVersionArg = versionPlan.nextVersionOverride,
                         useDefaults = useDefaults(ctx)
                       )
                     }
      releaseVer  <- resolveReleaseVersion(data, ctx.interactive, allowPrompts)
      nextVer     <- resolveNextVersion(data, releaseVer, ctx.interactive, allowPrompts)
    } yield ResolvedVersions(
      versionFile = versionPlan.versionFile,
      currentVersion = currentVer,
      releaseVersion = releaseVer,
      nextVersion = nextVer
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

  private def readVersionPrompt(
      prompt: String,
      promptContext: String,
      defaultVersion: String
  ): IO[String] =
    IO.print(prompt) *>
      StepHelpers.readRequiredLine(promptContext).flatMap(parseVersionInput(_, defaultVersion))

  private def resolveReleaseVersion(
      data: InquireData,
      interactive: Boolean,
      allowPrompts: Boolean
  ): IO[String] =
    data.releaseVersionArg match {
      case Some(versionValue)                                        =>
        parseVersionInput(versionValue, versionValue)
      case None if !interactive || data.useDefaults || !allowPrompts =>
        IO.pure(data.suggestedRelease)
      case None                                                      =>
        IO.blocking(data.state.log.info("Press enter to use the default value")) *>
          readVersionPrompt(
            prompt = s"Release version [${data.suggestedRelease}] : ",
            promptContext = "Release version",
            defaultVersion = data.suggestedRelease
          )
    }

  private def resolveNextVersion(
      data: InquireData,
      releaseVersion: String,
      interactive: Boolean,
      allowPrompts: Boolean
  ): IO[String] =
    data.nextVersionArg match {
      case Some(versionValue) => parseVersionInput(versionValue, versionValue)
      case None               =>
        IO(data.nextVersionFn(releaseVersion)).flatMap { suggestedNext =>
          if (!interactive || data.useDefaults || !allowPrompts) IO.pure(suggestedNext)
          else
            readVersionPrompt(
              prompt = s"Next version [$suggestedNext] : ",
              promptContext = "Next version",
              defaultVersion = suggestedNext
            )
        }
    }

  private def ensureVersionFileExists(versionFile: File): IO[Unit] =
    IO.blocking(versionFile.exists()).flatMap { exists =>
      if (exists) IO.unit
      else
        IO.raiseError(
          new IllegalStateException(
            s"Version file not found: ${versionFile.getPath}. " +
              "Create it with contents like `version := \"0.1.0-SNAPSHOT\"`, " +
              "or configure `releaseIOVersionFile`, `releaseIOReadVersion`, and " +
              "`releaseIOVersionFileContents`. See `releaseIO help` for setup details."
          )
        )
    }

  private def commitVersionNative(
      ctx: ReleaseContext,
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
            _      <- vcs.add(relativePath)
            status <- VcsOps.trackedStatus(vcs)
            result <- if (status.nonEmpty) {
                        for {
                          commitData        <- IO.blocking(SbtRuntime.runTask(ctx.state, commitMessageKey))
                          (commitState, msg) = commitData
                          _                 <- vcs.commit(msg, sign, signOff)
                          hash              <- vcs.currentHash
                        } yield (ctx.withState(commitState), hash)
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
