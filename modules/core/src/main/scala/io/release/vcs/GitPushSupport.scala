package io.release.vcs

import cats.effect.IO

private[release] object GitPushSupport {

  final case class GitPushTarget(
      remote: String,
      localBranch: String,
      upstreamBranch: String
  )

  def resolvePushTarget(vcs: Vcs): IO[GitPushTarget] =
    for {
      localBranch   <- vcs.currentBranch
      remote        <- vcs.trackingRemote
      upstreamRef   <- GitProcessSupport.runSingleLine(
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
    GitProcessSupport.runCmd(
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
    GitProcessSupport.runCmd(vcs.baseDir, Seq("push", remote, tag))(s"git push tag '$tag'")
}
