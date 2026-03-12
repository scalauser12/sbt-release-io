package io.release.monorepo.steps

import _root_.io.release.monorepo.{
  MonorepoContext,
  MonorepoProjectFailure,
  MonorepoProjectFailures,
  MonorepoRuntime,
  ProjectReleaseInfo
}
import _root_.io.release.steps.StepHelpers.{parseVersionInput, required}
import cats.effect.IO
import _root_.io.release.ReleaseIO.{releaseIOVcsSign, releaseIOVcsSignOff}
import sbt.{internal => _, *}

import scala.util.control.NonFatal

/** Shared helpers used across monorepo release step objects. */
private[monorepo] object MonorepoStepHelpers {

  /** If any project is marked failed, propagate failure to the global context. */
  def propagateFailures(ctx: MonorepoContext): MonorepoContext =
    if (ctx.projects.exists(_.failed)) {
      val failures = ctx.projects.collect {
        case project if project.failed =>
          MonorepoProjectFailure(project.name, project.failureCause)
      }
      ctx.failWith(new MonorepoProjectFailures(failures))
    } else ctx

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
            action(currentCtx, latestProj).handleErrorWith {
              case NonFatal(err) =>
                IO.blocking(
                  currentCtx.state.log.error(
                    s"[release-io-monorepo] ${latestProj.name}: ${Option(err.getMessage).getOrElse(err.toString)}"
                  )
                ) *> IO.pure(
                  currentCtx.updateProject(latestProj.ref)(
                    _.copy(failed = true, failureCause = Some(err))
                  )
                )
              case fatal         => IO.raiseError(fatal)
            }
        }
      }

  // ── Logging ───────────────────────────────────────────────────────────

  def logInfo(ctx: MonorepoContext, msg: String): IO[MonorepoContext] =
    IO.blocking(ctx.state.log.info(s"[release-io-monorepo] $msg")).as(ctx)

  def logWarn(ctx: MonorepoContext, msg: String): IO[MonorepoContext] =
    IO.blocking(ctx.state.log.warn(s"[release-io-monorepo] $msg")).as(ctx)

  /** Prompt user to continue, respecting interactive mode and useDefaults.
    * Non-interactive: hard-fail with abortMessage.
    * Interactive + useDefaults: use defaultYes.
    * Interactive: prompt user.
    */
  def confirmContinue(
      ctx: MonorepoContext,
      prompt: String,
      defaultYes: Boolean,
      abortMessage: String
  ): IO[Unit] = {
    if (!ctx.interactive)
      IO.raiseError(new IllegalStateException(abortMessage))
    else {
      val decisionIO =
        if (_root_.io.release.steps.StepHelpers.useDefaults(ctx.state)) IO.pure(defaultYes)
        else _root_.io.release.steps.StepHelpers.askYesNo(prompt, defaultYes = defaultYes)

      decisionIO.flatMap { continue =>
        if (continue) IO.unit
        else IO.raiseError(new IllegalStateException(abortMessage))
      }
    }
  }

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
        IO.print(s"$label [$suggested] : ") *>
          IO.readLine.flatMap(parseVersionInput(_, suggested))
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
        new IllegalStateException(
          s"$context:\n$detail"
        )
      )
    } else IO.unit
  }

  // ── VCS path resolution ───────────────────────────────────────────────

  /** Resolve version file paths relative to VCS root for all non-failed projects. */
  private[steps] def resolveRelativePaths(
      ctx: MonorepoContext,
      vcs: _root_.io.release.vcs.Vcs
  ): IO[Seq[(ProjectReleaseInfo, String)]] =
    loadRuntime(ctx).flatMap(resolveRelativePaths(ctx, vcs, _))

  private def resolveRelativePaths(
      ctx: MonorepoContext,
      vcs: _root_.io.release.vcs.Vcs,
      runtime: MonorepoRuntime
  ): IO[Seq[(ProjectReleaseInfo, String)]] =
    ctx.currentProjects.foldLeft(IO.pure(Seq.empty[(ProjectReleaseInfo, String)])) {
      (acc, project) =>
        acc.flatMap { paths =>
          val versionFile = resolveVersionFile(runtime, project)
          _root_.io.release.VcsOps
            .relativizeToBase(vcs, versionFile)
            .map(rel => paths :+ (project, rel))
        }
    }

  private[steps] def loadRuntime(ctx: MonorepoContext): IO[MonorepoRuntime] =
    IO.blocking(_root_.io.release.monorepo.MonorepoRuntime.fromState(ctx.state))

  private[steps] def resolveVersionFile(
      runtime: MonorepoRuntime,
      project: ProjectReleaseInfo
  ): File =
    _root_.io.release.monorepo.MonorepoVersionFiles.resolve(runtime, project.ref)

  private[steps] def resolveVersionFile(
      ctx: MonorepoContext,
      project: ProjectReleaseInfo
  ): IO[File] =
    MonorepoVersionSteps.resolve(ctx.state, project.ref).map(_.versionFile)

  // ── VCS commit ────────────────────────────────────────────────────────

  /** Stage version files, then commit if there are changes. */
  private[steps] def commitIfChanged(
      vcs: _root_.io.release.vcs.Vcs,
      msg: String,
      sign: Boolean,
      signOff: Boolean,
      ctx: MonorepoContext
  ): IO[MonorepoContext] =
    for {
      trackedStatus <- _root_.io.release.VcsOps.trackedStatus(vcs)
      result        <- if (trackedStatus.nonEmpty)
                         vcs.commit(msg, sign, signOff) *>
                           logInfo(ctx, s"Committed: $msg")
                       else
                         IO.pure(ctx)
    } yield result

  /** Stage and commit version files for all non-failed projects. */
  def commitVersions(
      ctx: MonorepoContext,
      msgPrefix: String,
      selector: ((String, String)) => String
  ): IO[MonorepoContext] =
    required(ctx.vcs, "VCS not initialized") { vcs =>
      for {
        runtime        <- loadRuntime(ctx)
        paths          <- resolveRelativePaths(ctx, vcs, runtime)
        settings       <- IO.blocking {
                            (
                              runtime.extracted.get(releaseIOVcsSign),
                              runtime.extracted.get(releaseIOVcsSignOff)
                            )
                          }
        (sign, signOff) = settings
        result         <- {
          // In global-version mode, all projects must agree before committing.
          val consistencyCheck =
            if (runtime.useGlobalVersion)
              validateVersionConsistency(
                ctx.currentProjects,
                selector,
                "Global version mode requires all projects to have the same version"
              )
            else IO.unit

          consistencyCheck *>
            paths.foldLeft(IO.unit) { case (acc, (_, relativePath)) =>
              acc *> vcs.add(relativePath)
            } *> {
              val summary = versionSummary(ctx, selector)
              commitIfChanged(vcs, s"$msgPrefix: $summary", sign, signOff, ctx)
            }
        }
      } yield result
    }
}
