package io.release.steps

import cats.effect.IO
import scala.sys.process.*
import io.release.{ReleaseContext, ReleaseKeys, ReleaseStepIO}
import io.release.ReleaseIO.{releaseIOReadVersion, releaseIOWriteVersion}
import sbt.*
import sbt.Keys.*
import sbt.Project.extract
import sbt.Package.ManifestAttributes
import sbtrelease.ReleasePlugin.autoImport.*

import StepHelpers.*

/** Version-related release steps: inquire, set, commit versions. */
private[release] object VersionSteps {

  private val versionPattern = """(?:ThisBuild\s*/\s*)?version\s*:=\s*"([^"]+)"""".r

  /** Default version file reader. Parses `[ThisBuild /] version := "x.y.z"`. */
  val defaultReadVersion: File => IO[String] = { file =>
    IO.blocking(sbt.IO.read(file)).flatMap { contents =>
      versionPattern.findFirstMatchIn(contents) match {
        case Some(m) => IO.pure(m.group(1))
        case None    =>
          IO.raiseError(
            new RuntimeException(
              s"Could not parse version from ${file.getName}. " +
                s"""Expected format: [ThisBuild /] version := "x.y.z"\nContents:\n$contents"""
            )
          )
      }
    }
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
      currentVer   <- {
        val extracted     = extract(ctx.state)
        val versionFile   = extracted.get(releaseVersionFile)
        val readVersionFn = extracted.get(releaseIOReadVersion)
        readVersionFn(versionFile)
      }
      data         <- IO.blocking {
                        val extracted = extract(ctx.state)

                        val (s1, releaseFn) = extracted.runTask(releaseVersion, ctx.state)
                        val (s2, nextFn)    = extracted.runTask(releaseNextVersion, s1)

                        InquireData(
                          state = s2,
                          currentVersion = currentVer,
                          suggestedRelease = releaseFn(currentVer),
                          nextVersionFn = nextFn,
                          releaseVersionArg = s2.get(ReleaseKeys.commandLineReleaseVersion).flatten,
                          nextVersionArg = s2.get(ReleaseKeys.commandLineNextVersion).flatten,
                          useDefaults = s2.get(ReleaseKeys.useDefaults).getOrElse(false)
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
      updated      <- IO {
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
        commitVersionNative(ctx, releaseCommitMessage).flatMap { case (resultCtx, currentHash) =>
          IO.blocking {
            val extracted        = extract(resultCtx.state)
            val useGlobalVersion = extracted.get(releaseUseGlobalVersion)
            val versionSetting   =
              if (useGlobalVersion) ThisBuild / version := releaseVer
              else version                              := releaseVer
            val newState         = extracted.appendWithSession(
              Seq(
                packageOptions += ManifestAttributes("Vcs-Release-Hash" -> currentHash),
                versionSetting
              ),
              resultCtx.state
            )
            resultCtx.copy(state = newState)
          }
        }
      }
  )

  val commitNextVersion: ReleaseStepIO =
    ReleaseStepIO.io("commit-next-version") { ctx =>
      commitVersionNative(ctx, releaseNextCommitMessage).map(_._1)
    }

  // --- private helpers ---

  private def readVersionPrompt(prompt: String, defaultVersion: String): IO[String] =
    IO.print(prompt) *>
      IO.readLine.flatMap { raw =>
        val input = Option(raw).map(_.trim).getOrElse("")
        if (input.isEmpty) IO.pure(defaultVersion)
        else {
          sbtrelease.Version(input).map(_.unapply) match {
            case Some(v) => IO.pure(v)
            case None    =>
              IO.raiseError(
                new RuntimeException(s"Invalid version format: '$input'")
              )
          }
        }
      }

  private def commitVersionNative(
      ctx: ReleaseContext,
      commitMessageKey: TaskKey[String]
  ): IO[(ReleaseContext, String)] =
    required(ctx.vcs, "VCS not initialized. Ensure initializeVcs runs before this step.") { vcs =>
      IO.blocking {
        val extracted    = extract(ctx.state)
        val versionFile  = extracted.get(releaseVersionFile).getCanonicalFile
        val base         = vcs.baseDir.getCanonicalFile
        val sign         = extracted.get(releaseVcsSign)
        val signOff      = extracted.get(releaseVcsSignOff)
        val relativePath = sbt.IO
          .relativize(base, versionFile)
          .getOrElse(
            throw new RuntimeException(
              s"[release-io] Version file [$versionFile] is outside of VCS root [$base]"
            )
          )

        runProcess(vcs.add(relativePath), s"vcs add '$relativePath'")

        val statusOutput = {
          val sb   = new StringBuilder
          val code = vcs.status.!(ProcessLogger(line => sb.append(line).append('\n'), _ => ()))
          if (code != 0)
            throw new RuntimeException(s"vcs status failed with exit code $code")
          sb.toString.trim
        }
        val status       = statusOutput.linesIterator
          .filterNot(_.startsWith("?"))
          .mkString("\n")

        if (status.nonEmpty) {
          val (commitState, msg) = extracted.runTask(commitMessageKey, ctx.state)
          runProcess(vcs.commit(msg, sign, signOff), "vcs commit")
          (ctx.copy(state = commitState), vcs.currentHash)
        } else {
          (ctx, vcs.currentHash)
        }
      }
    }

  private def writeVersion(ctx: ReleaseContext, ver: String): IO[ReleaseContext] = {
    val extracted   = extract(ctx.state)
    val versionFile = extracted.get(releaseVersionFile)
    val writeFn     = extracted.get(releaseIOWriteVersion)

    for {
      contents <- writeFn(versionFile, ver)
      result   <- IO.blocking {
                    java.nio.file.Files.write(versionFile.toPath, contents.getBytes("UTF-8"))
                    ctx.state.log.info(s"[release-io] Wrote version $ver to ${versionFile.getName}")
                    val useGlobal = extracted.get(releaseUseGlobalVersion)
                    val setting   = if (useGlobal) ThisBuild / version := ver else version := ver
                    val newState  = extracted.appendWithSession(Seq(setting), ctx.state)
                    ctx.copy(state = newState)
                  }
    } yield result
  }
}
