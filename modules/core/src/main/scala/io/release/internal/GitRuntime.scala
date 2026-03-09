package io.release.internal

import cats.effect.IO
import io.release.ReleaseCtx
import io.release.VcsOps
import io.release.steps.StepHelpers.runProcess
import sbt.*
import sbtrelease.Vcs

import scala.sys.process.ProcessLogger

/** Shared VCS helpers used by the built-in release implementations. */
private[release] object GitRuntime {

  def detectAndInit[C <: ReleaseCtx[C]](ctx: C): IO[C] =
    VcsOps.detectAndInit(ctx)

  def detectVcs(state: State): IO[Vcs] =
    VcsOps.detectVcs(state)

  def checkCleanWorkingDir(state: State): IO[VcsOps.CleanCheckResult] =
    VcsOps.checkCleanWorkingDir(state)

  def relativizeToBase(vcs: Vcs, file: File): IO[String] =
    IO.blocking(vcs.baseDir.getCanonicalFile).flatMap { base =>
      IO.blocking(file.getCanonicalFile).flatMap { canonical =>
        IO.fromOption(sbt.IO.relativize(base, canonical))(
          new IllegalStateException(
            s"Version file [$canonical] is outside of VCS root [$base]"
          )
        )
      }
    }

  def add(vcs: Vcs, path: String): IO[Unit] =
    runProcess(vcs.add(path), s"vcs add '$path'")

  def commit(vcs: Vcs, msg: String, sign: Boolean, signOff: Boolean): IO[Unit] =
    runProcess(vcs.commit(msg, sign, signOff), "vcs commit")

  def tag(vcs: Vcs, tagName: String, comment: String, sign: Boolean): IO[Unit] =
    runProcess(vcs.tag(tagName, comment, sign = sign), s"vcs tag '$tagName'")

  def pushChanges(vcs: Vcs): IO[Unit] =
    runProcess(vcs.pushChanges, "vcs push")

  def trackedStatus(vcs: Vcs): IO[String] =
    IO.blocking {
      val sb   = new StringBuilder
      val code = vcs.status.!(ProcessLogger(line => sb.append(line).append('\n'), _ => ()))
      (code, sb.toString.trim)
    }.flatMap { case (code, output) =>
      if (code != 0)
        IO.raiseError(new IllegalStateException(s"vcs status failed with exit code $code"))
      else
        IO.pure(output.linesIterator.filterNot(_.startsWith("?")).mkString("\n"))
    }
}
