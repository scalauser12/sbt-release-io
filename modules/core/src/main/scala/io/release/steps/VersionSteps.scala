package io.release.steps

import cats.effect.IO
import io.release.ReleaseIO.{releaseIOReadVersion, releaseIOWriteVersion}
import io.release.internal.{CoreReleasePlanner, GitRuntime, SbtRuntime, VersionPlan}
import io.release.{ReleaseContext, ReleaseKeys, ReleaseStepIO}
import sbt.*
import _root_.io.release.steps.StepHelpers.*
import sbt.Keys.*
import sbt.Package.ManifestAttributes
import sbtrelease.ReleasePlugin.autoImport.*

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
    val versionPlan = resolveVersionPlan(ctx.state)

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
      currentVer   <- versionPlan.readVersion(versionPlan.versionFile)
      data         <- IO.blocking {
                        val (s1, releaseFn) = SbtRuntime.runTask(ctx.state, releaseVersion)
                        val (s2, nextFn)    = SbtRuntime.runTask(s1, releaseNextVersion)

                        InquireData(
                          state = s2,
                          currentVersion = currentVer,
                          suggestedRelease = releaseFn(currentVer),
                          nextVersionFn = nextFn,
                          releaseVersionArg = versionPlan.releaseVersionOverride,
                          nextVersionArg = versionPlan.nextVersionOverride,
                          useDefaults = CoreReleasePlanner
                            .current(s2)
                            .map(_.flags.useDefaults)
                            .orElse(s2.get(ReleaseKeys.useDefaults))
                            .getOrElse(false)
                        )
                      }
      releaseVer   <-
        data.releaseVersionArg match {
          case Some(v)                                      => IO.pure(v)
          case None if !ctx.interactive || data.useDefaults => IO.pure(data.suggestedRelease)
          case None                                         =>
            IO(data.state.log.info("Press enter to use the default value")) *>
              readVersionPrompt(
                prompt = s"Release version [${data.suggestedRelease}] : ",
                defaultVersion = data.suggestedRelease
              )
        }
      suggestedNext = data.nextVersionFn(releaseVer)
      nextVer      <-
        data.nextVersionArg match {
          case Some(v)                                      => IO.pure(v)
          case None if !ctx.interactive || data.useDefaults => IO.pure(suggestedNext)
          case None                                         =>
            readVersionPrompt(
              prompt = s"Next version [${suggestedNext}] : ",
              defaultVersion = suggestedNext
            )
        }
      updated      <- IO.blocking {
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
    check = VcsSteps.checkCleanWorkingDirInternal(_, logStartHash = false),
    action = ctx =>
      requireVersions(ctx) { case (releaseVer, _) =>
        for {
          commitResult            <- commitVersionNative(ctx, releaseCommitMessage)
          (resultCtx, currentHash) = commitResult
          finalCtx                <- IO.blocking {
                                       val versionPlan    = resolveVersionPlan(resultCtx.state)
                                       val versionSetting =
                                         if (versionPlan.useGlobalVersion) ThisBuild / version := releaseVer
                                         else version                                          := releaseVer
                                       val newState       = SbtRuntime.appendWithSession(
                                         resultCtx.state,
                                         Seq(
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
      commitVersionNative(ctx, releaseNextCommitMessage).map(_._1)
    }

  private def readVersionPrompt(prompt: String, defaultVersion: String): IO[String] =
    for {
      _      <- IO.print(prompt)
      raw    <- IO.readLine
      result <- {
        val input = Option(raw).map(_.trim).getOrElse("")
        if (input.isEmpty) IO.pure(defaultVersion)
        else
          IO.fromOption(sbtrelease.Version(input).map(_.unapply))(
            new IllegalArgumentException(s"Invalid version format: '$input'")
          )
      }
    } yield result

  private def commitVersionNative(
      ctx: ReleaseContext,
      commitMessageKey: TaskKey[String]
  ): IO[(ReleaseContext, String)] =
    required(ctx.vcs, "VCS not initialized. Ensure initializeVcs runs before this step.") { vcs =>
      val versionPlan = resolveVersionPlan(ctx.state)
      val sign        = SbtRuntime.getSetting(ctx.state, releaseVcsSign)
      val signOff     = SbtRuntime.getSetting(ctx.state, releaseVcsSignOff)

      GitRuntime.relativizeToBase(vcs, versionPlan.versionFile).flatMap { relativePath =>
        for {
          _      <- GitRuntime.add(vcs, relativePath)
          status <- GitRuntime.trackedStatus(vcs)
          result <- if (status.nonEmpty) {
                      for {
                        commitData        <- IO.blocking(SbtRuntime.runTask(ctx.state, commitMessageKey))
                        (commitState, msg) = commitData
                        _                 <- GitRuntime.commit(vcs, msg, sign, signOff)
                        r                 <- IO.blocking(
                                               (ctx.copy(state = commitState), vcs.currentHash)
                                             )
                      } yield r
                    } else {
                      IO.blocking((ctx, vcs.currentHash))
                    }
        } yield result
      }
    }

  private def writeVersion(ctx: ReleaseContext, ver: String): IO[ReleaseContext] = {
    val versionPlan = resolveVersionPlan(ctx.state)

    for {
      contents <- versionPlan.writeVersion(versionPlan.versionFile, ver)
      result   <- IO.blocking {
                    java.nio.file.Files
                      .write(versionPlan.versionFile.toPath, contents.getBytes("UTF-8"))
                    ctx.state.log.info(
                      s"[release-io] Wrote version $ver to ${versionPlan.versionFile.getName}"
                    )
                    val setting  =
                      if (versionPlan.useGlobalVersion) ThisBuild / version := ver else version := ver
                    val newState = SbtRuntime.appendWithSession(ctx.state, Seq(setting))
                    ctx.copy(state = newState)
                  }
    } yield result
  }

  private def resolveVersionPlan(state: State): VersionPlan =
    CoreReleasePlanner.current(state).map(_.version).getOrElse {
      VersionPlan(
        versionFile = SbtRuntime.getSetting(state, releaseVersionFile),
        readVersion = SbtRuntime.getSetting(state, releaseIOReadVersion),
        writeVersion = SbtRuntime.getSetting(state, releaseIOWriteVersion),
        releaseVersionOverride = state.get(ReleaseKeys.commandLineReleaseVersion).flatten,
        nextVersionOverride = state.get(ReleaseKeys.commandLineNextVersion).flatten,
        useGlobalVersion = SbtRuntime.getSetting(state, releaseUseGlobalVersion)
      )
    }
}
