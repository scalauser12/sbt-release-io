package io.release.monorepo.steps

import cats.effect.IO
import io.release.ReleaseIO.releaseIONextVersion
import io.release.ReleaseIO.releaseIOVersion
import io.release.internal.ReleaseLogPrefixes
import io.release.internal.SbtRuntime
import io.release.monorepo.steps.MonorepoStepHelpers.*
import io.release.monorepo.steps.MonorepoVcsCommitHelpers.commitVersions
import io.release.monorepo.{MonorepoReleaseIO as MR, *}
import io.release.steps.StepHelpers
import io.release.steps.StepHelpers.parseVersionInput
import sbt.Keys.*
import sbt.{internal as _, *}

import java.nio.charset.StandardCharsets
import java.nio.file.Files

/** Version-related monorepo release steps: inquire, set, commit. */
private[monorepo] object MonorepoVersionSteps {

  private[monorepo] final case class ResolvedProjectVersions(
      versionFile: File,
      currentVersion: String,
      releaseVersion: String,
      nextVersion: String
  )

  /** Inquire release and next versions for each project.
    * If the project already has versions pre-populated (from command-line overrides),
    * those are used directly without prompting or computing.
    */
  val inquireVersions: MonorepoStepIO.PerProject = MonorepoStepIO.PerProject(
    name = "inquire-versions",
    validate = (ctx, project) =>
      MonorepoVersionFiles.resolveInputs(ctx.state, project.ref).flatMap { versionInputs =>
        IO.blocking(versionInputs.versionFile.exists()).flatMap { exists =>
          if (!exists)
            IO.raiseError(
              new IllegalStateException(
                s"Version file not found for ${project.name}: ${versionInputs.versionFile.getPath}. " +
                  "Create it with contents like `version := \"0.1.0-SNAPSHOT\"`, or configure " +
                  "`releaseIOMonorepoVersionFile`, `releaseIOMonorepoReadVersion`, and " +
                  "`releaseIOMonorepoVersionFileContents`. See `releaseIOMonorepo help`."
              )
            )
          else IO.unit
        }
      },
    execute = (ctx, project) =>
      project.versions match {
        case Some((rel, next)) if rel.nonEmpty && next.nonEmpty =>
          MonorepoVersionFiles.resolveInputs(ctx.state, project.ref).flatMap { versionInputs =>
            logInfo(ctx, s"${project.name}: pre-set -> $rel (next: $next)")
              .as(
                ctx.updateProject(project.ref)(
                  _.copy(versionFile = versionInputs.versionFile, versions = Some((rel, next)))
                )
              )
          }
        case _                                                  =>
          MonorepoVersionFiles.resolveInputs(ctx.state, project.ref).flatMap { versionInputs =>
            inquireVersionsInteractive(ctx, project)
          }
      }
  )

  private def inquireVersionsInteractive(
      ctx: MonorepoContext,
      project: ProjectReleaseInfo
  ): IO[MonorepoContext] =
    for {
      resolved <- resolveProjectVersions(
                    ctx,
                    project,
                    allowPrompts = true
                  )
      result   <- logInfo(
                    ctx,
                    s"${project.name}: ${resolved.currentVersion} -> " +
                      s"${resolved.releaseVersion} " +
                      s"(next: ${resolved.nextVersion})"
                  ).as(
                    ctx.updateProject(project.ref)(
                      _.copy(
                        versionFile = resolved.versionFile,
                        versions = Some(
                          (
                            resolved.releaseVersion,
                            resolved.nextVersion
                          )
                        )
                      )
                    )
                  )
    } yield result

  /** Write release versions to per-project version files. */
  val setReleaseVersions: MonorepoStepIO.PerProject = MonorepoStepIO.PerProject(
    name = "set-release-version",
    execute = (ctx, project) =>
      project.versions match {
        case Some((releaseVer, _)) => writeProjectVersion(ctx, project, releaseVer)
        case None                  =>
          IO.raiseError(new IllegalStateException(s"Versions not set for ${project.name}"))
      }
  )

  /** Write next snapshot versions to per-project version files. */
  val setNextVersions: MonorepoStepIO.PerProject = MonorepoStepIO.PerProject(
    name = "set-next-version",
    execute = (ctx, project) =>
      project.versions match {
        case Some((_, nextVer)) => writeProjectVersion(ctx, project, nextVer)
        case None               =>
          IO.raiseError(new IllegalStateException(s"Versions not set for ${project.name}"))
      }
  )

  /** Single commit for all release version files. */
  val commitReleaseVersions: MonorepoStepIO.Global = MonorepoStepIO.Global(
    name = "commit-release-versions",
    execute = ctx =>
      commitVersions(ctx, MR.releaseIOMonorepoCommitMessage, { case (releaseVer, _) => releaseVer })
  )

  /** Single commit for all next version files. */
  val commitNextVersions: MonorepoStepIO.Global = MonorepoStepIO.Global(
    name = "commit-next-versions",
    execute = ctx =>
      commitVersions(ctx, MR.releaseIOMonorepoNextCommitMessage, { case (_, nextVer) => nextVer })
  )

  // --- private helpers ---

  /** Fail fast when any configured monorepo projects resolve to the same physical version file.
    * Runs at write time (inside `writeProjectVersion`) so it sees the current state after any
    * late-bound steps that may have mutated `releaseIOMonorepoVersionFile`. Checks all projects
    * from `releaseIOMonorepoProjects`, not just the selected subset, so that a partial release
    * still detects a shared file that would be mutated for unreleased siblings.
    */
  private def validateDistinctVersionFiles(runtime: MonorepoRuntime): IO[Unit] = {
    val entries = runtime.extracted.get(MR.releaseIOMonorepoProjects).map { ref =>
      ref.project -> MonorepoVersionFiles.resolve(runtime, ref).getCanonicalPath
    }
    val byPath  = entries.groupBy(_._2).filter(_._2.length > 1)
    if (byPath.isEmpty) IO.unit
    else {
      val details = byPath
        .map { case (path, projects) =>
          s"${projects.map(_._1).mkString(", ")} -> $path"
        }
        .mkString("; ")
      IO.raiseError(
        new IllegalStateException(
          "Multiple projects resolve to the same version file: " + details + ". " +
            "Each monorepo project needs its own version file. " +
            "If you are upgrading from global version mode, " +
            "create per-project version.sbt files and remove any " +
            "`ThisBuild / releaseIOVersionFile` override. " +
            "See `releaseIOMonorepo help` for setup guidance."
        )
      )
    }
  }

  private def writeProjectVersion(
      ctx: MonorepoContext,
      project: ProjectReleaseInfo,
      ver: String
  ): IO[MonorepoContext] =
    for {
      runtime      <- IO.blocking(MonorepoRuntime.fromState(ctx.state))
      _            <- validateDistinctVersionFiles(runtime)
      versionInputs = MonorepoVersionFiles.resolveInputs(runtime, project.ref)
      preserved     = MonorepoVersionFiles.sessionSettings(runtime)
      versionFile   = versionInputs.versionFile
      result       <- for {
                        contents <- versionInputs.versionFileContents(versionFile, ver)
                        _        <- IO.blocking {
                                      Files.write(
                                        versionFile.toPath,
                                        contents.getBytes(StandardCharsets.UTF_8)
                                      )
                                    }
                        newState <- IO.blocking {
                                      SbtRuntime.appendWithSession(
                                        ctx.state,
                                        preserved ++ Seq(project.ref / version := ver)
                                      )
                                    }
                        updated   = ctx
                                      .withState(newState)
                                      .updateProject(project.ref)(_.copy(versionFile = versionFile))
                        _        <- logInfo(
                                      updated,
                                      s"Wrote version $ver to ${versionFile.getPath} for ${project.name}"
                                    )
                      } yield updated
    } yield result

  /** Resolve a version from an override, a default, or an interactive prompt. */
  private def promptOrDefault(
      override_ : Option[String],
      suggested: String,
      label: String,
      interactive: Boolean,
      useDefaults: Boolean,
      allowPrompts: Boolean = true
  ): IO[String] = override_.filter(_.nonEmpty) match {
    case Some(v) => parseVersionInput(v, v)
    case None    =>
      if (!interactive || useDefaults || !allowPrompts) IO.pure(suggested)
      else
        IO.print(s"$label [$suggested] : ") *>
          IO.readLine.flatMap(parseVersionInput(_, suggested))
  }

  private[monorepo] def resolveProjectVersions(
      ctx: MonorepoContext,
      project: ProjectReleaseInfo,
      allowPrompts: Boolean
  ): IO[ResolvedProjectVersions] =
    for {
      versionInputs                          <- MonorepoVersionFiles.resolveInputs(ctx.state, project.ref)
      _                                      <- IO.blocking(versionInputs.versionFile.exists()).flatMap { exists =>
                                                  if (exists) IO.unit
                                                  else
                                                    IO.raiseError(
                                                      new IllegalStateException(
                                                        s"Version file not found for ${project.name}: " +
                                                          s"${versionInputs.versionFile.getPath}. " +
                                                          "See `releaseIOMonorepo help`."
                                                      )
                                                    )
                                                }
      currentVer                             <- versionInputs.readVersion(versionInputs.versionFile)
      data                                   <- IO.blocking {
                                                  val (s1, releaseFn) =
                                                    SbtRuntime.runTask(ctx.state, project.ref / releaseIOVersion)
                                                  val (_, nextFn)     =
                                                    SbtRuntime.runTask(s1, project.ref / releaseIONextVersion)
                                                  val useDefaults     = StepHelpers.useDefaults(ctx)
                                                  (releaseFn(currentVer), nextFn, useDefaults)
                                                }
      (suggestedRelease, nextFn, useDefaults) = data
      releaseVer                             <- promptOrDefault(
                                                  project.releaseVersion.filter(_.nonEmpty),
                                                  suggestedRelease,
                                                  s"Release version for ${project.name}",
                                                  ctx.interactive,
                                                  useDefaults,
                                                  allowPrompts = allowPrompts
                                                )
      nextVer                                <- promptOrDefault(
                                                  project.nextVersion.filter(_.nonEmpty),
                                                  nextFn(releaseVer),
                                                  s"Next version for ${project.name}",
                                                  ctx.interactive,
                                                  useDefaults,
                                                  allowPrompts = allowPrompts
                                                )
    } yield ResolvedProjectVersions(
      versionFile = versionInputs.versionFile,
      currentVersion = currentVer,
      releaseVersion = releaseVer,
      nextVersion = nextVer
    )
}
