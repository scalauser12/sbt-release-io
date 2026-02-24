package io.release.monorepo.steps

import cats.effect.IO
import _root_.io.release.monorepo.{MonorepoContext, ProjectReleaseInfo}
import _root_.io.release.steps.StepHelpers.runProcess
import sbt.*
import sbt.Project.extract
import sbtrelease.ReleasePlugin.autoImport.*

import scala.sys.process.*

/** Shared helpers used across monorepo release step objects. */
private[monorepo] object MonorepoStepHelpers {

  def required[A, B](opt: Option[A], error: String)(f: A => IO[B]): IO[B] =
    opt.fold(IO.raiseError[B](new RuntimeException(error)))(f)

  /** Run a per-project action across all non-failed projects, with error isolation.
    * Each project failure is logged and marks the project as failed without aborting others.
    */
  def runPerProject(
      ctx: MonorepoContext,
      action: (MonorepoContext, ProjectReleaseInfo) => IO[MonorepoContext]
  ): IO[MonorepoContext] =
    ctx.currentProjects
      .foldLeft(IO.pure(ctx)) { (ioCtx, proj) =>
        ioCtx.flatMap { currentCtx =>
          val latestProj = currentCtx.projects.find(_.ref == proj.ref).getOrElse(proj)
          if (latestProj.failed) IO.pure(currentCtx)
          else
            action(currentCtx, latestProj).handleErrorWith { err =>
              IO(
                currentCtx.state.log.error(
                  s"[release-io-monorepo] ${latestProj.name}: ${Option(err.getMessage).getOrElse(err.toString)}"
                )
              ) *> IO.pure(
                currentCtx.updateProject(latestProj.ref)(_.copy(failed = true))
              )
            }
        }
      }

  // ── Logging ───────────────────────────────────────────────────────────

  def logInfo(ctx: MonorepoContext, msg: String): IO[MonorepoContext] =
    IO(ctx.state.log.info(s"[release-io-monorepo] $msg")).as(ctx)

  def logWarn(ctx: MonorepoContext, msg: String): IO[MonorepoContext] =
    IO(ctx.state.log.warn(s"[release-io-monorepo] $msg")).as(ctx)

  // ── Version summaries ─────────────────────────────────────────────────

  /** Comma-separated summary of project versions, e.g. "core 1.0.0, api 1.0.0". */
  def versionSummary(
      ctx: MonorepoContext,
      selector: ((String, String)) => String
  ): String =
    ctx.currentProjects
      .flatMap(p => p.versions.map(v => s"${p.name} ${selector(v)}"))
      .mkString(", ")

  // ── Version prompting ─────────────────────────────────────────────────

  /** Resolve a version from an override, a default, or an interactive prompt. */
  def promptOrDefault(
      override_ : Option[String],
      suggested: String,
      label: String,
      interactive: Boolean,
      useDefaults: Boolean
  ): IO[String] = override_.filter(_.nonEmpty) match {
    case Some(v) => IO.pure(v)
    case None    =>
      if (!interactive || useDefaults) IO.pure(suggested)
      else
        IO.print(s"$label [$suggested] : ") *> IO.readLine.map { raw =>
          val input = Option(raw).map(_.trim).getOrElse("")
          if (input.isEmpty) suggested else input
        }
  }

  // ── Version consistency ───────────────────────────────────────────────

  /** Validate that all projects agree on a version dimension. Raises on mismatch. */
  def validateVersionConsistency(
      projects: Seq[ProjectReleaseInfo],
      selector: ((String, String)) => String,
      context: String
  ): IO[Unit] = {
    val versions = projects.flatMap(p => p.versions.map(v => p.name -> selector(v)))
    val distinct = versions.map(_._2).distinct
    if (distinct.length > 1) {
      val detail = versions.map { case (n, v) => s"  $n -> $v" }.mkString("\n")
      IO.raiseError(
        new RuntimeException(
          s"[release-io-monorepo] $context:\n$detail"
        )
      )
    } else IO.unit
  }

  // ── VCS path resolution ───────────────────────────────────────────────

  /** Resolve version file paths relative to VCS root for all non-failed projects. */
  private[steps] def resolveRelativePaths(
      ctx: MonorepoContext,
      vcs: sbtrelease.Vcs
  ): IO[Seq[(ProjectReleaseInfo, String)]] = {
    val base = vcs.baseDir.getCanonicalFile
    ctx.currentProjects.foldLeft(IO.pure(Seq.empty[(ProjectReleaseInfo, String)])) {
      (acc, project) =>
        acc.flatMap { paths =>
          val versionFile = resolveVersionFile(ctx, project)
          sbt.IO.relativize(base, versionFile.getCanonicalFile) match {
            case Some(rel) => IO.pure(paths :+ (project, rel))
            case None      =>
              IO.raiseError(
                new RuntimeException(
                  s"Version file [${versionFile.getCanonicalPath}] is outside VCS root [$base]"
                )
              )
          }
        }
    }
  }

  private[steps] def resolveVersionFile(
      ctx: MonorepoContext,
      project: ProjectReleaseInfo
  ): File = {
    val extracted = extract(ctx.state)
    val useGlobal =
      extracted.get(_root_.io.release.monorepo.MonorepoReleaseIO.releaseIOMonorepoUseGlobalVersion)
    if (useGlobal)
      extracted.get(sbtrelease.ReleasePlugin.autoImport.releaseVersionFile)
    else {
      val versionFileFn =
        extracted.get(_root_.io.release.monorepo.MonorepoReleaseIO.releaseIOMonorepoVersionFile)
      versionFileFn(project.ref)
    }
  }

  // ── VCS commit ────────────────────────────────────────────────────────

  /** Stage version files, then commit if there are changes. */
  private[steps] def commitIfChanged(
      vcs: sbtrelease.Vcs,
      msg: String,
      sign: Boolean,
      signOff: Boolean,
      ctx: MonorepoContext
  ): IO[MonorepoContext] =
    IO.blocking {
      val sb   = new StringBuilder
      val code = vcs.status.!(ProcessLogger(line => sb.append(line).append('\n'), _ => ()))
      if (code != 0) throw new RuntimeException(s"vcs status failed with exit code $code")
      sb.toString.trim.linesIterator.filterNot(_.startsWith("?")).mkString("\n")
    }.flatMap { status =>
      if (status.nonEmpty)
        runProcess(vcs.commit(msg, sign, signOff), "vcs commit") *>
          logInfo(ctx, s"Committed: $msg")
      else
        IO.pure(ctx)
    }

  /** Stage and commit version files for all non-failed projects. */
  def commitVersions(
      ctx: MonorepoContext,
      msgPrefix: String,
      selector: ((String, String)) => String
  ): IO[MonorepoContext] =
    required(ctx.vcs, "VCS not initialized") { vcs =>
      resolveRelativePaths(ctx, vcs).flatMap { paths =>
        val extracted = extract(ctx.state)
        val sign      = extracted.get(releaseVcsSign)
        val signOff   = extracted.get(releaseVcsSignOff)

        paths.foldLeft(IO.unit) { case (acc, (_, relativePath)) =>
          acc *> runProcess(vcs.add(relativePath), s"vcs add '$relativePath'")
        } *> {
          val summary = versionSummary(ctx, selector)
          commitIfChanged(vcs, s"$msgPrefix: $summary", sign, signOff, ctx)
        }
      }
    }
}
