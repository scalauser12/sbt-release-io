package io.release.monorepo.steps

import cats.effect.IO
import io.release.LoadCompat
import io.release.ReleaseComposer
import io.release.internal.ReleaseLogPrefixes
import io.release.internal.SbtRuntime
import io.release.monorepo.*
import sbt.Def.ScopedKey
import sbt.Keys.*
import sbt.util.Show
import sbt.{internal as _, *}

/** Cross-build executor for monorepo per-project steps.
  *
  * When cross-build is active, each project's action is executed once per
  * `crossScalaVersions` entry with Scala version switching and restore-on-error.
  * FailureCommand detection and project-failure short-circuiting are handled
  * uniformly for both the project loop and the version loop.
  */
private[monorepo] object MonorepoCrossBuild {

  private val LogPrefix = ReleaseLogPrefixes.Monorepo

  private def isScalaSessionSetting(setting: Setting[?]): Boolean =
    setting.key match {
      case ScopedKey(Scope(_, Zero, Zero, _), key)
          if key == Keys.scalaVersion.key || key == Keys.scalaHome.key =>
        true
      case _ => false
    }

  private[monorepo] def rethrowWithRestoreFailure(
      ctx: MonorepoContext,
      original: Throwable,
      restoreFailure: Throwable
  ): IO[Nothing] =
    IO.blocking {
      ctx.state.log.error(
        s"$LogPrefix Failed to restore the entry Scala version after a cross-build failure: " +
          s"${Option(restoreFailure.getMessage).getOrElse(restoreFailure.toString)}"
      )
      ReleaseComposer.attachSuppressed(original, restoreFailure)
    } *> IO.raiseError(original)

  /** Run a per-project action with optional cross-build iteration. */
  def runPerProjectWithCrossBuild(
      ctx: MonorepoContext,
      action: (MonorepoContext, ProjectReleaseInfo) => IO[MonorepoContext],
      crossBuild: Boolean,
      enableCrossBuild: Boolean
  ): IO[MonorepoContext] =
    if (crossBuild && enableCrossBuild)
      MonorepoStepHelpers.runPerProject(ctx, (c, p) => runCrossBuildForProject(c, p, action))
    else
      MonorepoStepHelpers.runPerProject(ctx, action)

  /** Run a per-project validation with optional cross-build iteration.
    * When cross-build is active, validation runs once per `crossScalaVersions` entry.
    */
  def validatePerProjectWithCrossBuild(
      ctx: MonorepoContext,
      validate: (MonorepoContext, ProjectReleaseInfo) => IO[MonorepoContext],
      crossBuild: Boolean,
      enableCrossBuild: Boolean
  ): IO[MonorepoContext] = {
    val projects = ctx.currentProjects.toList

    if (crossBuild && enableCrossBuild)
      projects.foldLeft(IO.pure(ctx)) { (ioCtx, project) =>
        ioCtx.flatMap { currentCtx =>
          runCrossBuildForProject(
            currentCtx,
            project,
            (innerCtx, _) => validate(innerCtx, project)
          )
        }
      }
    else
      projects.foldLeft(IO.pure(ctx)) { (ioCtx, project) =>
        ioCtx.flatMap(currentCtx => validate(currentCtx, project))
      }
  }

  /** Cross-build a single project across its `crossScalaVersions`.
    * Reads cross-build settings, validates non-empty, then delegates to
    * [[iterateVersions]] for the actual version-switching loop.
    */
  private def runCrossBuildForProject(
      ctx: MonorepoContext,
      project: ProjectReleaseInfo,
      action: (MonorepoContext, ProjectReleaseInfo) => IO[MonorepoContext]
  ): IO[MonorepoContext] = IO.defer {
    IO.blocking {
      val extracted     = SbtRuntime.extracted(ctx.state)
      val crossVersions =
        (project.ref / crossScalaVersions).get(extracted.structure.data).getOrElse(Seq.empty)
      val entryVersion  =
        (extracted.currentRef / scalaVersion)
          .get(extracted.structure.data)
          .orElse((GlobalScope / scalaVersion).get(extracted.structure.data))
      (crossVersions, entryVersion, ctx.state)
    }.flatMap { case (crossVersions, entryVersion, entryState) =>
      if (crossVersions.isEmpty)
        IO.raiseError(
          new IllegalStateException(
            s"$LogPrefix Cross-build enabled but ${project.name} has empty crossScalaVersions"
          )
        )
      else {
        val switcher = new VersionSwitcher(project, entryState, entryVersion)
        iterateVersions(ctx, project, crossVersions, action, switcher)
      }
    }
  }

  /** Execute the action once per Scala version, with failure short-circuiting
    * and entry-version restoration.
    */
  private def iterateVersions(
      ctx: MonorepoContext,
      project: ProjectReleaseInfo,
      crossVersions: Seq[String],
      action: (MonorepoContext, ProjectReleaseInfo) => IO[MonorepoContext],
      switcher: VersionSwitcher
  ): IO[MonorepoContext] = {
    def detectFailure(c: MonorepoContext): IO[MonorepoContext] =
      MonorepoStepHelpers.detectProjectFailureCommand(c, project)

    if (crossVersions.length == 1)
      switcher
        .runIteration(ctx, crossVersions.head, action, project) {
          s"$LogPrefix Cross-building ${project.name} with Scala ${crossVersions.head}"
        }
        .flatMap(detectFailure)
        .flatMap(switcher.restoreAfterCompletion)
    else
      crossVersions.toList
        .foldLeft(IO.pure(ctx)) { (ioCtx, version) =>
          ioCtx.flatMap { currentCtx =>
            val projectFailed =
              currentCtx.projects.exists(p => p.ref == project.ref && p.failed)
            if (currentCtx.failed || projectFailed) IO.pure(currentCtx)
            else
              switcher
                .runIteration(currentCtx, version, action, project) {
                  s"$LogPrefix Cross-building with Scala $version"
                }
                .flatMap(detectFailure)
          }
        }
        .flatMap(switcher.restoreAfterCompletion)
  }

  /** Encapsulates Scala version switching and restoration for a single cross-build run. */
  private class VersionSwitcher(
      project: ProjectReleaseInfo,
      entryState: State,
      entryVersion: Option[String]
  ) {

    def switchTo(version: String)(ctx: MonorepoContext): IO[MonorepoContext] =
      SbtRuntime.switchScalaVersion(ctx.state, version).map(ctx.withState)

    private def restoreEntryScalaSession(ctx: MonorepoContext): IO[MonorepoContext] =
      IO.blocking {
        val currentExtracted                          = SbtRuntime.extracted(ctx.state)
        val entryExtracted                            = SbtRuntime.extracted(entryState)
        import currentExtracted.*
        implicit val showKey: Show[ScopedKey[?]]      = currentExtracted.showKey

        val currentSettingsWithoutScala = session.mergeSettings.filterNot(isScalaSessionSetting)
        val entryScalaSettings          =
          entryExtracted.session.mergeSettings.filter(isScalaSessionSetting)
        val newStructure                =
          LoadCompat.reapply(entryScalaSettings ++ currentSettingsWithoutScala, structure)
        val restoredState               = Project.setProject(session, newStructure, ctx.state)

        ctx.withState(restoredState)
      }

    def restoreEntry(ctx: MonorepoContext): IO[MonorepoContext] =
      entryVersion match {
        case Some(ver) => switchTo(ver)(ctx)
        case None      => restoreEntryScalaSession(ctx)
      }

    def restoreAfterCompletion(ctx: MonorepoContext): IO[MonorepoContext] =
      restoreEntry(ctx).attempt.flatMap {
        case Right(restoredCtx) => IO.pure(restoredCtx)
        case Left(restoreErr)   =>
          IO.blocking {
            ctx.state.log.error(
              s"$LogPrefix Failed to restore the entry Scala version after cross-building " +
                s"${project.name}: ${Option(restoreErr.getMessage).getOrElse(restoreErr.toString)}"
            )
          } *> IO.raiseError(restoreErr)
      }

    def runIteration(
        ctx: MonorepoContext,
        version: String,
        action: (MonorepoContext, ProjectReleaseInfo) => IO[MonorepoContext],
        project: ProjectReleaseInfo
    )(logMessage: String): IO[MonorepoContext] =
      for {
        _        <- IO.blocking(ctx.state.log.info(logMessage))
        switched <- switchTo(version)(ctx)
        result   <- action(switched, project).attempt.flatMap {
                      case Right(nextCtx) => IO.pure(nextCtx)
                      case Left(err)      =>
                        restoreEntry(switched).attempt.flatMap {
                          case Right(_)         => IO.raiseError(err)
                          case Left(restoreErr) =>
                            rethrowWithRestoreFailure(switched, err, restoreErr)
                        }
                    }
      } yield result
  }
}
