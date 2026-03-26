package io.release.steps

import cats.effect.IO
import io.release.ReleaseContext
import io.release.ReleaseIO.*
import io.release.ReleaseStepIO
import io.release.VcsOps
import io.release.internal.ReleaseLogPrefixes
import io.release.internal.SbtRuntime
import io.release.internal.VersionPlan
import io.release.steps.StepHelpers.*
import sbt.Keys.*
import sbt.Package.ManifestAttributes
import sbt.{internal as _, *}

/** Version-related release steps: inquire, set, commit versions. */
private[release] object VersionSteps {

  private[steps] final case class ResolvedSettings(
      versionFile: File,
      readVersion: File => IO[String],
      versionFileContents: (File, String) => IO[String],
      useGlobalVersion: Boolean
  )

  private[release] final case class ResolvedVersions(
      versionFile: File,
      currentVersion: String,
      releaseVersion: String,
      nextVersion: String
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

  private def resolve(
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

  private final case class InquireData(
      state: State,
      currentVersion: String,
      suggestedRelease: String,
      nextVersionFn: String => String,
      releaseVersionArg: Option[String],
      nextVersionArg: Option[String],
      useDefaults: Boolean
  )

  private val versionPattern = """(?:ThisBuild\s*/\s*)?version\s*:=\s*"([^"]+)"""".r

  /** Default version file reader. Parses `[ThisBuild /] version := "x.y.z"`.
    * Skips comment lines to avoid matching commented-out versions.
    */
  val defaultReadVersion: File => IO[String] = { file =>
    for {
      contents <- IO.blocking(sbt.IO.read(file))
      result   <- IO.fromOption {
                    contents
                      .replaceAll("""(?s)/\*.*?\*/""", "")
                      .linesIterator
                      .map(_.trim)
                      .filterNot(_.startsWith("//"))
                      .flatMap(versionPattern.findFirstMatchIn(_).map(_.group(1)))
                      .buffered
                      .headOption
                  }(
                    new IllegalStateException(
                      s"Could not parse version from ${file.getName}. " +
                        s"""Expected format: [ThisBuild /] version := "x.y.z"\nContents:\n$contents\n""" +
                        "If you use a custom version file format, configure " +
                        "`releaseIOVersionFile`, `releaseIOReadVersion`, and " +
                        "`releaseIOVersionFileContents`. See `releaseIO help` for examples."
                    )
                  )
    } yield result
  }

  /** Default version file writer. Produces `[ThisBuild /] version := "x.y.z"`. */
  def defaultWriteVersion(useGlobalVersion: Boolean): (File, String) => IO[String] =
    (_, ver) => {
      val key = if (useGlobalVersion) "ThisBuild / version" else "version"
      IO.pure(s"""$key := "$ver"\n""")
    }

  val inquireVersions: ReleaseStepIO = ReleaseStepIO(
    name = "inquire-versions",
    validate =
      ctx => IO.blocking(resolveVersionPlan(ctx).versionFile).flatMap(ensureVersionFileExists),
    execute = { ctx =>
      for {
        resolved <- resolveVersions(ctx, allowPrompts = true)
        updated  <- IO.blocking {
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
      } yield updated
    }
  )

  val setReleaseVersion: ReleaseStepIO =
    ReleaseStepIO.io("set-release-version") { ctx =>
      requireVersions(ctx) { case (releaseVer, _) =>
        writeVersion(ctx, releaseVer)
      }
    }

  val setNextVersion: ReleaseStepIO = ReleaseStepIO.io("set-next-version") { ctx =>
    requireVersions(ctx) { case (_, nextVer) =>
      writeVersion(ctx, nextVer)
    }
  }

  val commitReleaseVersion: ReleaseStepIO = ReleaseStepIO(
    name = "commit-release-version",
    validate = VcsSteps.validateCleanWorkingDir(_, logStartHash = false),
    execute = ctx =>
      requireVersions(ctx) { case (releaseVer, _) =>
        for {
          versionPlan             <- IO.blocking(resolveVersionPlan(ctx))
          commitResult            <- commitVersionNative(ctx, releaseIOCommitMessage, versionPlan.versionFile)
          (resultCtx, currentHash) = commitResult
          finalCtx                <- IO.blocking {
                                       val versionSettings =
                                         if (versionPlan.useGlobalVersion)
                                           Seq(ThisBuild / version := releaseVer, version := releaseVer)
                                         else Seq(version          := releaseVer)
                                       val newState        = SbtRuntime.appendWithSession(
                                         resultCtx.state,
                                         VersionSteps.sessionSettings(versionPlan) ++ Seq(
                                           packageOptions += ManifestAttributes(
                                             "Vcs-Release-Hash" -> currentHash
                                           )
                                         ) ++ versionSettings
                                       )
                                       resultCtx.withState(newState)
                                     }
        } yield finalCtx
      }
  )

  val commitNextVersion: ReleaseStepIO =
    ReleaseStepIO.io("commit-next-version") { ctx =>
      requireVersions(ctx) { case (_, nextVer) =>
        for {
          versionPlan <- IO.blocking(resolveVersionPlan(ctx))
          result      <- commitVersionNative(ctx, releaseIONextCommitMessage, versionPlan.versionFile)
          finalCtx    <- IO.blocking {
                           val versionSettings =
                             if (versionPlan.useGlobalVersion)
                               Seq(ThisBuild / version := nextVer, version := nextVer)
                             else Seq(version          := nextVer)
                           val newState        = SbtRuntime.appendWithSession(
                             result._1.state,
                             VersionSteps.sessionSettings(versionPlan) ++ versionSettings
                           )
                           result._1.withState(newState)
                         }
        } yield finalCtx
      }
    }

  private def readVersionPrompt(prompt: String, defaultVersion: String): IO[String] =
    IO.print(prompt) *> IO.readLine.flatMap(parseVersionInput(_, defaultVersion))

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

  private def resolveReleaseVersion(
      data: InquireData,
      interactive: Boolean,
      allowPrompts: Boolean
  ): IO[String] =
    data.releaseVersionArg match {
      case Some(v)                                                   => parseVersionInput(v, v)
      case None if !interactive || data.useDefaults || !allowPrompts =>
        IO.pure(data.suggestedRelease)
      case None                                                      =>
        IO.blocking(data.state.log.info("Press enter to use the default value")) *>
          readVersionPrompt(
            prompt = s"Release version [${data.suggestedRelease}] : ",
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
      case Some(v) => parseVersionInput(v, v)
      case None    =>
        IO(data.nextVersionFn(releaseVersion)).flatMap { suggestedNext =>
          if (!interactive || data.useDefaults || !allowPrompts) IO.pure(suggestedNext)
          else
            readVersionPrompt(
              prompt = s"Next version [$suggestedNext] : ",
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

  private def writeVersion(ctx: ReleaseContext, ver: String): IO[ReleaseContext] = {
    for {
      versionPlan <- IO.blocking(resolveVersionPlan(ctx))
      contents    <- versionPlan.versionFileContents(versionPlan.versionFile, ver)
      _           <- IO.blocking {
                       java.nio.file.Files
                         .write(versionPlan.versionFile.toPath, contents.getBytes("UTF-8"))
                       ctx.state.log.info(
                         s"${ReleaseLogPrefixes.Core} Wrote version $ver to ${versionPlan.versionFile.getName}"
                       )
                     }
      result      <- IO.blocking {
                       val versionSettings =
                         if (versionPlan.useGlobalVersion)
                           Seq(ThisBuild / version := ver, version := ver)
                         else Seq(version          := ver)
                       val allSettings     =
                         VersionSteps.sessionSettings(versionPlan) ++ versionSettings
                       val newState        =
                         SbtRuntime.appendWithSession(ctx.state, allSettings)
                       ctx.withState(newState)
                     }
    } yield result
  }

  private[release] def resolveVersionPlan(
      ctx: ReleaseContext,
      resolveSettings: State => ResolvedSettings = resolveCurrentSettings
  ): VersionPlan =
    resolve(ctx, resolveSettings)
}
