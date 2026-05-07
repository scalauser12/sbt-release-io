package io.release.monorepo.internal.steps

import cats.effect.IO
import io.release.LoadCompat
import io.release.ReleaseSharedKeys.releaseIOVersioningBump
import io.release.VcsOps
import io.release.monorepo.*
import io.release.monorepo.MonorepoReleasePlugin
import io.release.monorepo.internal.*
import io.release.monorepo.internal.steps.MonorepoStepHelpers.*
import io.release.runtime.workflow.StepHelpers
import io.release.version.Version
import io.release.vcs.Vcs
import sbt.{internal as _, *}

/** Internal helpers for [[MonorepoVersionWorkflow]]: task-key resolution with bump
  * fallback, VCS detection, and version-file safety checks.
  */
private[steps] object MonorepoVersionResolvers {

  def missingVersionTaskWarning(
      projectRef: ProjectRef,
      taskKey: TaskKey[?],
      fallback: String
  ): String =
    s"${projectRef.project}: ${taskKey.key.label} is undefined; falling back to $fallback"

  def resolveCurrentVcs(ctx: MonorepoContext): IO[Vcs] =
    ctx.vcs match {
      case Some(vcs) => IO.pure(vcs)
      case None      => VcsOps.detectVcs(ctx.state)
    }

  def resolveVersionFunction(
      ctx: MonorepoContext,
      projectRef: ProjectRef,
      taskKey: TaskKey[String => String],
      defaultForBump: Version.Bump => (String => String),
      actionName: String
  ): IO[(MonorepoContext, String => String)] =
    if (LoadCompat.containsScopedKey(ctx.state, projectRef / taskKey))
      StepHelpers
        .runTaskChecked(ctx.state, projectRef / taskKey, actionName)
        .map { case (nextState, fn) =>
          (ctx.withState(nextState), fn)
        }
    else
      logWarn(
        ctx,
        missingVersionTaskWarning(
          projectRef,
          taskKey,
          s"built-in defaults from ${releaseIOVersioningBump.key.label}"
        )
      ) *>
        resolveVersionBump(ctx, projectRef, actionName).map { case (bumpCtx, bump) =>
          (bumpCtx, defaultForBump(bump))
        }

  def resolveVersionBump(
      ctx: MonorepoContext,
      projectRef: ProjectRef,
      actionName: String
  ): IO[(MonorepoContext, Version.Bump)] =
    if (LoadCompat.containsScopedKey(ctx.state, projectRef / releaseIOVersioningBump))
      StepHelpers
        .runTaskChecked(ctx.state, projectRef / releaseIOVersioningBump, actionName)
        .map { case (nextState, bump) =>
          (ctx.withState(nextState), bump)
        }
    else
      logWarn(
        ctx,
        missingVersionTaskWarning(
          projectRef,
          releaseIOVersioningBump,
          s"${Version.Bump.default}"
        )
      ).as((ctx, Version.Bump.default))

  def runWritePhaseValidation(
      ctx: MonorepoContext,
      selectedProjects: Seq[ProjectReleaseInfo],
      highlightedProjectRef: Option[ProjectRef]
  ): IO[Unit] =
    for {
      runtime <- IO.blocking(MonorepoRuntime.fromState(ctx.state))
      _       <- validateDistinctVersionFiles(runtime)
      vcs     <- resolveCurrentVcs(ctx)
      _       <- validateSelectedVersionFilesUnderVcsRoot(
                   runtime,
                   vcs,
                   selectedProjects,
                   highlightedProjectRef
                 )
    } yield ()

  /** Fail fast when any configured monorepo projects resolve to the same physical version file.
    * Runs once at the start of each version-write phase so it sees the current state after any
    * late-bound steps that may have mutated `releaseIOMonorepoVersioningFile`. Checks all projects
    * from `releaseIOMonorepoSelectionProjects`, not just the selected subset, so that a partial release
    * still detects a shared file that would be mutated for unreleased siblings.
    *
    * Path equality uses `getCanonicalPath` and groups by string equality, which inherits the
    * host filesystem's case sensitivity. On case-insensitive filesystems (macOS APFS default,
    * Windows NTFS default) two refs pointing at the same file via different cases canonicalize
    * to one entry and group correctly; on case-sensitive filesystems differently-cased paths
    * resolve to genuinely distinct files.
    */
  def validateDistinctVersionFiles(runtime: MonorepoRuntime): IO[Unit] =
    IO.blocking {
      val entries = runtime.extracted
        .get(MonorepoReleasePlugin.autoImport.releaseIOMonorepoSelectionProjects)
        .map { ref =>
          ref.project -> MonorepoVersionFiles.resolve(runtime, ref).getCanonicalPath
        }
      val byPath  = entries.groupBy(_._2).filter(_._2.length > 1)

      byPath.headOption.map { _ =>
        byPath
          .map { case (path, projects) =>
            s"${projects.map(_._1).mkString(", ")} -> $path"
          }
          .mkString("; ")
      }
    }.flatMap {
      case Some(details) =>
        IO.raiseError(
          new IllegalStateException(
            "Multiple projects resolve to the same version file: " + details + ". " +
              "Each monorepo project needs its own version file. " +
              "If you are upgrading from global version mode, " +
              "create per-project version.sbt files and remove any " +
              "`ThisBuild / releaseIOVersioningFile` override. " +
              "See `releaseIOMonorepo help` for setup guidance."
          )
        )
      case None          => IO.unit
    }

  def validateSelectedVersionFilesUnderVcsRoot(
      runtime: MonorepoRuntime,
      vcs: Vcs,
      selectedProjects: Seq[ProjectReleaseInfo],
      highlightedProjectRef: Option[ProjectRef]
  ): IO[Unit] =
    selectedProjects.foldLeft(IO.unit) { (validated, project) =>
      validated *>
        validateSelectedVersionFileUnderVcsRoot(
          vcs,
          project,
          MonorepoVersionFiles.resolve(runtime, project.ref),
          highlightedProjectRef.contains(project.ref)
        )
    }

  def validateSelectedVersionFileUnderVcsRoot(
      vcs: Vcs,
      project: ProjectReleaseInfo,
      versionFile: File,
      includeSelectedMarker: Boolean
  ): IO[Unit] =
    VcsOps.relativizeToBase(vcs, versionFile).void.recoverWith { case err: IllegalStateException =>
      IO.blocking {
        val selectedMarker =
          if (includeSelectedMarker) "selected " else ""
        val versionPath    = versionFile.getCanonicalPath
        val repoRoot       = vcs.baseDir.getCanonicalFile.getAbsolutePath

        new IllegalStateException(
          s"Resolved ${selectedMarker}version file for ${project.name} is outside the VCS root: " +
            s"$versionPath (repo root: $repoRoot). " +
            "Configure releaseIOMonorepoVersioningFile to return a file under the repository. " +
            "See `releaseIOMonorepo help`.",
          err
        )
      }.flatMap(IO.raiseError)
    }
}
