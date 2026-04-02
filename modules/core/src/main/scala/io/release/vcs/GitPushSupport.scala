package io.release.vcs

import cats.effect.IO

import java.io.File
import scala.sys.process.Process
import scala.sys.process.ProcessLogger

private[release] object GitPushSupport {

  final case class GitPushTarget(
      remote: String,
      localBranch: String,
      upstreamBranch: String
  )

  private lazy val exec: String = {
    val maybeWindows = sys.props.get("os.name").map(_.toLowerCase).exists(_.contains("windows"))
    if (maybeWindows) "git.exe" else "git"
  }

  def resolvePushTarget(vcs: Vcs): IO[GitPushTarget] =
    for {
      localBranch   <- vcs.currentBranch
      remote        <- vcs.trackingRemote
      upstreamRef   <- runSingleLine(
                         vcs.baseDir,
                         Seq(
                           "rev-parse",
                           "--abbrev-ref",
                           "--symbolic-full-name",
                           "@{upstream}"
                         )
                       )("git rev-parse --abbrev-ref --symbolic-full-name @{upstream}")
      remotePrefix   = s"$remote/"
      _             <-
        IO.raiseUnless(upstreamRef.startsWith(remotePrefix))(
          new IllegalStateException(
            s"Upstream '$upstreamRef' for branch '$localBranch' does not match tracking remote '$remote'."
          )
        )
      upstreamBranch = upstreamRef.stripPrefix(remotePrefix)
      _             <-
        IO.raiseWhen(upstreamBranch.isEmpty)(
          new IllegalStateException(
            s"Unable to resolve upstream branch from '$upstreamRef' for tracking remote '$remote'."
          )
        )
    } yield GitPushTarget(
      remote = remote,
      localBranch = localBranch,
      upstreamBranch = upstreamBranch
    )

  def pushTrackedBranch(vcs: Vcs, followTags: Boolean): IO[GitPushTarget] =
    resolvePushTarget(vcs).flatTap(pushTrackedBranch(vcs, _, followTags))

  def pushTrackedBranch(
      vcs: Vcs,
      target: GitPushTarget,
      followTags: Boolean
  ): IO[Unit] = {
    val followTagArgs = if (followTags) Seq("--follow-tags") else Seq.empty
    runCmd(
      vcs.baseDir,
      Seq("push") ++ followTagArgs ++ Seq(
        target.remote,
        s"${target.localBranch}:${target.upstreamBranch}"
      )
    )(
      if (followTags) "git push --follow-tags"
      else s"git push ${target.remote} ${target.localBranch}:${target.upstreamBranch}"
    )
  }

  def pushTag(vcs: Vcs, remote: String, tag: String): IO[Unit] =
    runCmd(vcs.baseDir, Seq("push", remote, tag))(s"git push tag '$tag'")

  private def runCmd(baseDir: File, args: Seq[String])(context: => String): IO[Unit] =
    IO.blocking(Process(exec +: args, baseDir).!).flatMap { code =>
      if (code != 0)
        IO.raiseError(new IllegalStateException(s"$context failed with exit code $code"))
      else IO.unit
    }

  private def runSingleLine(
      baseDir: File,
      args: Seq[String]
  )(context: => String): IO[String] =
    runLines(baseDir, args)(context).flatMap {
      case head +: _ => IO.pure(head)
      case _         =>
        IO.raiseError(
          new IllegalStateException(s"$context succeeded but returned no output")
        )
    }

  private def runLines(
      baseDir: File,
      args: Seq[String]
  )(context: => String): IO[Seq[String]] =
    IO.blocking {
      val stderr = new StringBuilder
      val lines  = List.newBuilder[String]
      val code   = Process(exec +: args, baseDir).!(
        ProcessLogger(
          line => { lines += line; () },
          err => { stderr.append(err).append('\n'); () }
        )
      )
      (code, lines.result(), stderr.toString.trim)
    }.flatMap { case (code, result, stderr) =>
      if (code != 0)
        IO.raiseError(
          new IllegalStateException(
            s"$context failed with exit code $code" +
              (if (stderr.nonEmpty) s": $stderr" else "")
          )
        )
      else IO.pure(result)
    }
}
