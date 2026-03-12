package io.release.steps

import cats.effect.IO
import io.release.internal.{CoreVersionResolver, SbtRuntime, VersionPlan}
import io.release.{ReleaseContext, ReleaseKeys, ReleaseStepIO, VcsOps}
import _root_.io.release.ReleaseIO.{
  releaseIOCommitMessage,
  releaseIONextCommitMessage,
  releaseIONextVersion,
  releaseIOVcsSign,
  releaseIOVcsSignOff,
  releaseIOVersion
}
import _root_.io.release.steps.StepHelpers.*
import sbt.{internal => _, *}
import sbt.Keys.*
import sbt.Package.ManifestAttributes

/** Version-related release steps: inquire, set, commit versions. */
private[release] object VersionSteps {

  private val versionPattern = """(?:ThisBuild\s*/\s*)?version\s*:=\s*"([^"]+)"""".r

  /** Default version file reader. Parses `[ThisBuild /] version := "x.y.z"`.
    * Skips comment lines to avoid matching commented-out versions.
    */
  val defaultReadVersion: File => IO[String] = { file =>
    for {
      contents <- IO.blocking(sbt.IO.read(file))
      result   <- IO.fromOption {
                    contents.linesIterator
                      .map(_.trim)
                      .filterNot(l => l.startsWith("//") || l.startsWith("/*") || l.startsWith("*"))
                      .collectFirst(
                        Function.unlift(versionPattern.findFirstMatchIn(_).map(_.group(1)))
                      )
                  }(
                    new IllegalStateException(
                      s"Could not parse version from ${file.getName}. " +
                        s"""Expected format: [ThisBuild /] version := "x.y.z"\nContents:\n$contents"""
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
      versionPlan   <- IO.blocking(resolveVersionPlan(ctx.state))
      currentVer    <- versionPlan.readVersion(versionPlan.versionFile)
      data          <- IO.blocking {
                         val releaseFn = SbtRuntime.getSetting(ctx.state, releaseIOVersion)
                         val nextFn    = SbtRuntime.getSetting(ctx.state, releaseIONextVersion)

                         InquireData(
                           state = ctx.state,
                           currentVersion = currentVer,
                           suggestedRelease = releaseFn(currentVer),
                           nextVersionFn = nextFn,
                           releaseVersionArg = versionPlan.releaseVersionOverride,
                           nextVersionArg = versionPlan.nextVersionOverride,
                           useDefaults = useDefaults(ctx.state)
                         )
                       }
      releaseVer    <-
        data.releaseVersionArg match {
          case Some(v)                                      => IO.pure(v)
          case None if !ctx.interactive || data.useDefaults => IO.pure(data.suggestedRelease)
          case None                                         =>
            IO.blocking(data.state.log.info("Press enter to use the default value")) *>
              readVersionPrompt(
                prompt = s"Release version [${data.suggestedRelease}] : ",
                defaultVersion = data.suggestedRelease
              )
        }
      suggestedNext <- IO(data.nextVersionFn(releaseVer))
      nextVer       <-
        data.nextVersionArg match {
          case Some(v)                                      => IO.pure(v)
          case None if !ctx.interactive || data.useDefaults => IO.pure(suggestedNext)
          case None                                         =>
            readVersionPrompt(
              prompt = s"Next version [${suggestedNext}] : ",
              defaultVersion = suggestedNext
            )
        }
      updated       <- IO.blocking {
                         data.state.log.info(s"[release-io] Current version : ${data.currentVersion}")
                         data.state.log.info(s"[release-io] Release version : $releaseVer")
                         data.state.log.info(s"[release-io] Next version    : $nextVer")

                         val updatedState = data.state.put(ReleaseKeys.versions, (releaseVer, nextVer))
                         ctx.copy(state = updatedState).withVersions(releaseVer, nextVer)
                       }
    } yield updated
  }

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
          versionPlan             <- IO.blocking(resolveVersionPlan(ctx.state))
          commitResult            <- commitVersionNative(ctx, releaseIOCommitMessage, versionPlan.versionFile)
          (resultCtx, currentHash) = commitResult
          finalCtx                <- IO.blocking {
                                       val versionSetting =
                                         if (versionPlan.useGlobalVersion) ThisBuild / version := releaseVer
                                         else version                                          := releaseVer
                                       val stateWithAttr  =
                                         resultCtx.state.put(ReleaseKeys.runtimeVersionOverride, releaseVer)
                                       val newState       = SbtRuntime.appendWithSession(
                                         stateWithAttr,
                                         CoreVersionResolver.sessionSettings(resultCtx.state) ++ Seq(
                                           packageOptions += ManifestAttributes(
                                             "Vcs-Release-Hash" -> currentHash
                                           ),
                                           versionSetting
                                         )
                                       )
                                       resultCtx.copy(state = newState)
                                     }
        } yield finalCtx
      }
  )

  val commitNextVersion: ReleaseStepIO =
    ReleaseStepIO.io("commit-next-version") { ctx =>
      for {
        versionPlan <- IO.blocking(resolveVersionPlan(ctx.state))
        result      <- commitVersionNative(ctx, releaseIONextCommitMessage, versionPlan.versionFile)
      } yield result._1
    }

  private def readVersionPrompt(prompt: String, defaultVersion: String): IO[String] =
    IO.print(prompt) *> IO.readLine.flatMap(parseVersionInput(_, defaultVersion))

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
                        } yield (ctx.copy(state = commitState), hash)
                      } else {
                        vcs.currentHash.map(hash => (ctx, hash))
                      }
          } yield result
        }
      }
    }

  private def writeVersion(ctx: ReleaseContext, ver: String): IO[ReleaseContext] = {
    for {
      versionPlan <- IO.blocking(resolveVersionPlan(ctx.state))
      contents    <- versionPlan.writeVersion(versionPlan.versionFile, ver)
      _           <- IO.blocking {
                       java.nio.file.Files
                         .write(versionPlan.versionFile.toPath, contents.getBytes("UTF-8"))
                       ctx.state.log.info(
                         s"[release-io] Wrote version $ver to ${versionPlan.versionFile.getName}"
                       )
                     }
      result      <- IO.blocking {
                       val versionSettings =
                         if (versionPlan.useGlobalVersion)
                           Seq(ThisBuild / version := ver, version := ver)
                         else Seq(version          := ver)
                       val allSettings     =
                         CoreVersionResolver.sessionSettings(ctx.state) ++ versionSettings
                       val stateWithAttr   =
                         ctx.state.put(ReleaseKeys.runtimeVersionOverride, ver)
                       val newState        =
                         SbtRuntime.appendWithSession(stateWithAttr, allSettings)
                       ctx.copy(state = newState)
                     }
    } yield result
  }

  private[release] def resolveVersionPlan(
      state: State,
      resolveSettings: State => CoreVersionResolver.ResolvedSettings =
        CoreVersionResolver.resolveCurrentSettings
  ): VersionPlan =
    CoreVersionResolver.resolve(state, resolveSettings)
}
