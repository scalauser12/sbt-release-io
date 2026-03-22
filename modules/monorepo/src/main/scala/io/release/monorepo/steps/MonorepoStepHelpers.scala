package io.release.monorepo.steps

import cats.effect.IO
import cats.syntax.all.*
import io.release.ReleaseIO.{releaseIOVcsSign, releaseIOVcsSignOff}
import io.release.VcsOps
import io.release.internal.{ExecutionEngine, ReleaseLogPrefixes, SbtRuntime}
import io.release.monorepo.*
import io.release.steps.StepHelpers.{errorMessage, parseVersionInput, required}
import io.release.vcs.Vcs
import sbt.Keys.*
import sbt.{internal as _, *}

import scala.util.control.NonFatal

/** Shared helpers used across monorepo release step objects. */
private[monorepo] object MonorepoStepHelpers {

  // ── Per-project execution ─────────────────────────────────────────────

  /** Run a per-project action across all non-failed projects, with error isolation.
    *
    * This is the single owner of per-project failure handling:
    *  - FailureCommand detection after each action (no step needs to handle this)
    *  - Exception isolation (a failing project doesn't abort others)
    *  - Failure propagation to the global context (callers don't need to call propagateFailures)
    *
    * Step implementations just run their sbt tasks and return the context.
    * All failure bookkeeping is handled here.
    */
  def runPerProject(
      ctx: MonorepoContext,
      action: (MonorepoContext, ProjectReleaseInfo) => IO[MonorepoContext]
  ): IO[MonorepoContext] =
    runPerProjectInternal(ctx, action).map(propagateFailures)

  /** Internal per-project fold without propagation — used by cross-build iteration
    * which needs to run multiple version iterations before propagating.
    */
  private def runPerProjectInternal(
      ctx: MonorepoContext,
      action: (MonorepoContext, ProjectReleaseInfo) => IO[MonorepoContext]
  ): IO[MonorepoContext] =
    ctx.currentProjects
      .foldLeft(IO.pure(ctx)) { (ioCtx, proj) =>
        ioCtx.flatMap { currentCtx =>
          val latestProj = currentCtx.projects.find(_.ref == proj.ref).getOrElse(proj)
          if (currentCtx.failed || latestProj.failed) IO.pure(currentCtx)
          else
            action(currentCtx, latestProj)
              .map(detectProjectFailureCommand(_, latestProj))
              .handleErrorWith { case NonFatal(err) =>
                IO.blocking(
                  currentCtx.state.log.error(
                    s"${ReleaseLogPrefixes.Monorepo} ${latestProj.name}: ${errorMessage(err)}"
                  )
                ) *> IO.pure(
                  currentCtx.updateProject(latestProj.ref)(
                    _.copy(failed = true, failureCause = Some(err))
                  )
                )
              }
        }
      }

  /** If any project is marked failed, propagate failure to the global context. */
  private def propagateFailures(ctx: MonorepoContext): MonorepoContext =
    if (ctx.projects.exists(_.failed)) {
      val failures = ctx.projects.collect {
        case project if project.failed =>
          MonorepoProjectFailure(project.name, project.failureCause)
      }
      ctx.failWith(new MonorepoProjectFailures(failures))
    } else ctx

  /** Detect and consume a FailureCommand sentinel left in the sbt state by a task.
    * If found, strips the sentinel, re-arms `onFailure`, and marks the project as failed.
    * Package-private so the cross-build loop in [[MonorepoComposer]] can also apply this
    * check after each Scala version iteration.
    */
  private[monorepo] def detectProjectFailureCommand(
      ctx: MonorepoContext,
      project: ProjectReleaseInfo
  ): MonorepoContext =
    if (SbtRuntime.hasFailureCommand(ctx.state)) {
      val failure = new IllegalStateException(
        s"${project.name}: sbt task reported failure via FailureCommand"
      )
      val cleaned = SbtRuntime.stripLeadingFailureCommand(ctx.state)
      val result  = ExecutionEngine
        .armOnFailure(ctx.withState(cleaned))
        .updateProject(project.ref)(_.copy(failed = true, failureCause = Some(failure)))
      result.state.log.error(s"${ReleaseLogPrefixes.Monorepo} ${failure.getMessage}")
      result
    } else ctx

  // ── Cross-build per-project execution ────────────────────────────────

  private val LogPrefix = ReleaseLogPrefixes.Monorepo

  /** Run a per-project action with optional cross-build iteration.
    * When cross-build is active, each project's action is executed once per
    * `crossScalaVersions` entry with Scala version switching and restore-on-error.
    * FailureCommand detection and project-failure short-circuiting are handled
    * uniformly for both the project loop and the version loop.
    */
  def runPerProjectWithCrossBuild(
      ctx: MonorepoContext,
      action: (MonorepoContext, ProjectReleaseInfo) => IO[MonorepoContext],
      crossBuild: Boolean,
      enableCrossBuild: Boolean
  ): IO[MonorepoContext] =
    if (crossBuild && enableCrossBuild)
      runPerProject(ctx, (c, p) => runCrossBuildForProject(c, p, action))
    else
      runPerProject(ctx, action)

  /** Run a per-project validation with optional cross-build iteration.
    * When cross-build is active, validation runs once per `crossScalaVersions` entry.
    */
  def validatePerProjectWithCrossBuild(
      ctx: MonorepoContext,
      validate: (MonorepoContext, ProjectReleaseInfo) => IO[Unit],
      crossBuild: Boolean,
      enableCrossBuild: Boolean
  ): IO[Unit] =
    if (crossBuild && enableCrossBuild)
      ctx.currentProjects.toList.traverse_ { project =>
        runCrossBuildForProject(
          ctx,
          project,
          (innerCtx, _) => validate(innerCtx, project).as(innerCtx)
        ).void
      }
    else
      ctx.currentProjects.toList.traverse_(validate(ctx, _))

  /** Cross-build a single project across its `crossScalaVersions`.
    * Switches Scala version before each iteration and restores the entry version afterward.
    * Short-circuits on project-level failure (detected via FailureCommand or exception).
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
      (crossVersions, entryVersion)
    }.flatMap { case (crossVersions, entryVersion) =>
      def switchTo(version: String)(currentCtx: MonorepoContext): IO[MonorepoContext] =
        SbtRuntime.switchScalaVersion(currentCtx.state, version).map(currentCtx.withState)

      def restoreEntry(currentCtx: MonorepoContext): IO[MonorepoContext] =
        entryVersion match {
          case Some(ver) => switchTo(ver)(currentCtx)
          case None      => IO.pure(currentCtx)
        }

      if (crossVersions.isEmpty)
        IO.raiseError(
          new IllegalStateException(
            s"$LogPrefix Cross-build enabled but ${project.name} has empty crossScalaVersions"
          )
        )
      else {
        def restoreOnError(
            currentCtx: MonorepoContext,
            err: Throwable
        ): IO[MonorepoContext] =
          restoreEntry(currentCtx).attempt *> IO.raiseError(err)

        def runIteration(
            currentCtx: MonorepoContext,
            version: String,
            logMessage: String
        ): IO[MonorepoContext] =
          for {
            _        <- IO.blocking(currentCtx.state.log.info(logMessage))
            switched <- switchTo(version)(currentCtx)
            result   <- action(switched, project).attempt.flatMap {
                          case Right(nextCtx) => IO.pure(nextCtx)
                          case Left(err)      => restoreOnError(switched, err)
                        }
          } yield result

        def detectFailure(c: MonorepoContext): MonorepoContext =
          detectProjectFailureCommand(c, project)

        if (crossVersions.length == 1)
          runIteration(
            ctx,
            crossVersions.head,
            s"$LogPrefix Cross-building ${project.name} with Scala ${crossVersions.head}"
          ).map(detectFailure)
            .flatMap(restoreEntry)
        else
          crossVersions.toList
            .foldLeft(IO.pure(ctx)) { (ioCtx, version) =>
              ioCtx.flatMap { currentCtx =>
                val projectFailed =
                  currentCtx.projects.exists(p => p.ref == project.ref && p.failed)
                if (currentCtx.failed || projectFailed) IO.pure(currentCtx)
                else
                  runIteration(
                    currentCtx,
                    version,
                    s"$LogPrefix Cross-building with Scala $version"
                  ).map(detectFailure)
              }
            }
            .flatMap(restoreEntry)
      }
    }
  }

  // ── Logging ───────────────────────────────────────────────────────────

  def logInfo(ctx: MonorepoContext, msg: String): IO[Unit] =
    IO.blocking(ctx.state.log.info(s"${ReleaseLogPrefixes.Monorepo} $msg"))

  def logWarn(ctx: MonorepoContext, msg: String): IO[Unit] =
    IO.blocking(ctx.state.log.warn(s"${ReleaseLogPrefixes.Monorepo} $msg"))

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

  /** Validate that all projects agree on a version dimension. Raises on mismatch.
    *
    * Called at multiple points by design:
    *  - `MonorepoVersionSteps.validateVersions` — catches mismatches early.
    *  - `commitVersions` below — precondition before committing.
    *  - `MonorepoVcsSteps.tagReleasesUnified` — precondition before unified tag.
    */
  def validateVersionConsistency(
      projects: Seq[ProjectReleaseInfo],
      selector: ((String, String)) => String,
      context: String
  ): IO[Unit] = {
    val missing = projects.filter(_.versions.isEmpty)
    if (missing.nonEmpty)
      IO.raiseError(
        new IllegalStateException(
          s"$context: projects missing version metadata: ${missing.map(_.name).mkString(", ")}"
        )
      )
    else {
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
  }

  // ── VCS path resolution ───────────────────────────────────────────────

  /** Resolve version file paths relative to VCS root for all non-failed projects. */
  private[steps] def resolveRelativePaths(
      ctx: MonorepoContext,
      vcs: Vcs
  ): IO[Seq[(ProjectReleaseInfo, String)]] =
    loadRuntime(ctx).flatMap(resolveRelativePaths(ctx, vcs, _))

  private def resolveRelativePaths(
      ctx: MonorepoContext,
      vcs: Vcs,
      runtime: MonorepoRuntime
  ): IO[Seq[(ProjectReleaseInfo, String)]] =
    ctx.currentProjects.toList.traverse { project =>
      val versionFile = resolveVersionFile(runtime, project)
      VcsOps.relativizeToBase(vcs, versionFile).map(rel => (project, rel))
    }

  private[steps] def loadRuntime(ctx: MonorepoContext): IO[MonorepoRuntime] =
    IO.blocking(MonorepoRuntime.fromState(ctx.state))

  private[steps] def resolveVersionFile(
      runtime: MonorepoRuntime,
      project: ProjectReleaseInfo
  ): File =
    MonorepoVersionFiles.resolve(runtime, project.ref)

  private[steps] def resolveVersionFile(
      ctx: MonorepoContext,
      project: ProjectReleaseInfo
  ): IO[File] =
    MonorepoVersionFiles.resolveInputs(ctx.state, project.ref).map(_.versionFile)

  // ── VCS commit ────────────────────────────────────────────────────────

  /** Stage version files, then commit if there are changes. */
  private[steps] def commitIfChanged(
      ctx: MonorepoContext,
      vcs: Vcs,
      msg: String,
      sign: Boolean,
      signOff: Boolean
  ): IO[MonorepoContext] =
    for {
      trackedStatus <- VcsOps.trackedStatus(vcs)
      result        <- if (trackedStatus.nonEmpty)
                         vcs.commit(msg, sign, signOff) *>
                           logInfo(ctx, s"Committed: $msg").as(ctx)
                       else
                         IO.pure(ctx)
    } yield result

  /** Stage and commit version files for all non-failed projects. */
  def commitVersions(
      ctx: MonorepoContext,
      msgFormatterKey: SettingKey[String => String],
      selector: ((String, String)) => String
  ): IO[MonorepoContext] =
    required(ctx.vcs, "VCS not initialized") { vcs =>
      for {
        runtime                      <- loadRuntime(ctx)
        paths                        <- resolveRelativePaths(ctx, vcs, runtime)
        settings                     <- IO.blocking {
                                          (
                                            runtime.extracted.get(releaseIOVcsSign),
                                            runtime.extracted.get(releaseIOVcsSignOff),
                                            runtime.extracted.get(msgFormatterKey)
                                          )
                                        }
        (sign, signOff, msgFormatter) = settings
        result                       <- {
          val consistencyCheck =
            if (runtime.useGlobalVersion)
              validateVersionConsistency(
                ctx.currentProjects,
                selector,
                "Global version mode requires all projects to have the same version"
              )
            else IO.unit

          consistencyCheck *>
            IO.uncancelable { _ =>
              paths.map(_._2).distinct.toList.traverse_(vcs.add(_)) *>
                {
                  val summary = versionSummary(ctx, selector)
                  commitIfChanged(ctx, vcs, msgFormatter(summary), sign, signOff)
                }
            }
        }
      } yield result
    }
}
