package io.release.vcs

import cats.effect.IO

private[release] object GitPushSupport {

  private val HeadsRefPrefix = "refs/heads/"

  final case class GitPushTarget(
      remote: String,
      localBranch: String,
      upstreamBranch: String
  )

  def resolvePushTarget(vcs: Vcs): IO[GitPushTarget] =
    for {
      localBranch   <- vcs.currentBranch
      rawRemote     <- vcs.trackingRemote
      remote         = rawRemote.trim
      _             <- IO.raiseWhen(remote.isEmpty)(
                         new IllegalStateException(
                           s"Tracking remote for branch '$localBranch' is empty; " +
                             s"configure branch.$localBranch.remote before releasing."
                         )
                       )
      _             <- IO.raiseWhen(remote == ".")(
                         new IllegalStateException(
                           s"Branch '$localBranch' tracks a local branch (branch.$localBranch.remote = '.'); " +
                             "configure a real remote before releasing."
                         )
                       )
      rawMergeRef   <- GitProcessSupport.runSingleLine(
                         vcs.baseDir,
                         Seq("config", s"branch.$localBranch.merge")
                       )(s"git config branch.$localBranch.merge")
      mergeRef       = rawMergeRef.trim
      _             <-
        IO.raiseUnless(mergeRef.startsWith(HeadsRefPrefix))(
          new IllegalStateException(
            s"Tracking branch ref '$mergeRef' for branch '$localBranch' must use the '$HeadsRefPrefix' format."
          )
        )
      upstreamBranch = mergeRef.stripPrefix(HeadsRefPrefix)
      _             <-
        IO.raiseWhen(upstreamBranch.isEmpty)(
          new IllegalStateException(
            s"Unable to resolve tracking branch from '$mergeRef' for remote '$remote' and branch '$localBranch'."
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
    IO.raiseWhen(tag.trim.isEmpty)(
      new IllegalStateException("Tag name cannot be empty when pushing to the remote.")
    ) *> {
      val tagRef = s"refs/tags/$tag"
      GitProcessSupport.runCmd(vcs.baseDir, Seq("push", remote, s"$tagRef:$tagRef"))(
        s"git push tag '$tag'"
      )
    }
}
