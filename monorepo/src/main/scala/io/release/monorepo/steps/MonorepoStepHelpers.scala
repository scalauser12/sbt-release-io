package io.release.monorepo.steps

import cats.effect.IO
import _root_.io.release.monorepo.{MonorepoContext, ProjectReleaseInfo}

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
}
