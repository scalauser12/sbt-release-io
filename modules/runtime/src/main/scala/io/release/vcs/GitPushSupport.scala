package io.release.vcs

import cats.effect.IO

/** Raised when tracking-branch configuration is present but shape-invalid (blank, `.`, or
  * a merge ref outside `refs/heads/`). Distinct from generic VCS failures so preflight
  * can propagate it while still recovering from missing-upstream-ref errors.
  */
private[release] final class InvalidUpstreamConfigException(message: String)
    extends IllegalStateException(message)

/** Git-specific push orchestration: resolves tracking-branch config into a validated
  * push target and performs branch/tag pushes.
  */
private[release] object GitPushSupport {

  private val HeadsRefPrefix = "refs/heads/"

  /** Resolved push target for a branch.
    *
    * @param remote         the tracking remote name.
    * @param localBranch    the local branch being pushed.
    * @param upstreamBranch the upstream branch name with `refs/heads/` stripped.
    */
  final case class GitPushTarget(
      remote: String,
      localBranch: String,
      upstreamBranch: String
  )

  /** Resolve the validated tracking-branch configuration for the current branch.
    * Raises [[InvalidUpstreamConfigException]] when the merge ref is unset, not under
    * `refs/heads/`, or empty after stripping the prefix.
    */
  def resolvePushTarget(vcs: Vcs): IO[GitPushTarget] =
    for {
      localBranch    <- vcs.currentBranch
      remote         <- vcs.trackingRemote
      mergeRef       <- readBranchConfig(
                          vcs,
                          localBranch,
                          "merge",
                          s"Branch '$localBranch' has no configured upstream branch; " +
                            s"configure branch.$localBranch.merge before releasing."
                        )
      upstreamBranch <- validateMergeRef(localBranch, mergeRef)
    } yield GitPushTarget(
      remote = remote,
      localBranch = localBranch,
      upstreamBranch = upstreamBranch
    )

  /** Validate a `branch.<name>.merge` value: must start with `refs/heads/` and have a
    * non-empty stripped form. Returns the stripped upstream branch name on success.
    */
  private[vcs] def validateMergeRef(branch: String, mergeRef: String): IO[String] = {
    val stripped = mergeRef.stripPrefix(HeadsRefPrefix)
    for {
      _ <- IO.raiseUnless(mergeRef.startsWith(HeadsRefPrefix))(
             new InvalidUpstreamConfigException(
               s"Tracking branch ref '$mergeRef' for branch '$branch' " +
                 s"must use the '$HeadsRefPrefix' format."
             )
           )
      _ <- IO.raiseWhen(stripped.isEmpty)(
             new InvalidUpstreamConfigException(
               s"Unable to resolve tracking branch from '$mergeRef' for branch '$branch'."
             )
           )
    } yield stripped
  }

  private[vcs] def readBranchConfig(
      vcs: Vcs,
      branch: String,
      key: String,
      missingMessage: => String
  ): IO[String] = {
    val args    = Seq("config", s"branch.$branch.$key")
    val context = s"git config branch.$branch.$key"
    GitProcessSupport.runCommandResult(vcs.baseDir, args).flatMap { result =>
      result.exitCode match {
        case 0 => IO.pure(result.stdout.headOption.fold("")(_.trim))
        case 1 => IO.raiseError(new InvalidUpstreamConfigException(missingMessage))
        case n =>
          val stderrSuffix = if (result.stderr.nonEmpty) s": ${result.stderr}" else ""
          IO.raiseError(
            new IllegalStateException(s"$context failed with exit code $n$stderrSuffix")
          )
      }
    }
  }

  /** Resolve the push target and push the current branch in one step.
    * Returns the resolved target so callers can log or reuse it.
    *
    * @param followTags when `true`, also push annotated tags reachable from the pushed commits.
    */
  def pushTrackedBranch(vcs: Vcs, followTags: Boolean): IO[GitPushTarget] =
    resolvePushTarget(vcs).flatTap(pushTrackedBranch(vcs, _, followTags))

  /** Push the current branch to a previously resolved target.
    *
    * @param followTags when `true`, also push annotated tags reachable from the pushed commits.
    */
  def pushTrackedBranch(
      vcs: Vcs,
      target: GitPushTarget,
      followTags: Boolean
  ): IO[Unit] = {
    val refspec        = s"${target.localBranch}:${target.upstreamBranch}"
    val followTagArgs  = if (followTags) Seq("--follow-tags") else Seq.empty
    val followTagLabel = if (followTags) " --follow-tags" else ""
    GitProcessSupport.runCmd(
      vcs.baseDir,
      Seq("push") ++ followTagArgs ++ Seq(target.remote, refspec)
    )(s"git push$followTagLabel ${target.remote} $refspec")
  }

  /** Push a single tag to the given remote.
    *
    * @param tag the tag name (without `refs/tags/` — the ref is built internally).
    */
  def pushTag(vcs: Vcs, remote: String, tag: String): IO[Unit] = {
    val trimmedRemote = remote.trim
    val trimmedTag    = tag.trim
    for {
      _     <- IO.raiseWhen(trimmedRemote.isEmpty)(
                 new IllegalStateException("Remote name cannot be empty when pushing a tag.")
               )
      _     <- IO.raiseWhen(trimmedTag.isEmpty)(
                 new IllegalStateException("Tag name cannot be empty when pushing to the remote.")
               )
      tagRef = s"refs/tags/$trimmedTag"
      _     <- GitProcessSupport.runCmd(
                 vcs.baseDir,
                 Seq("push", trimmedRemote, s"$tagRef:$tagRef")
               )(s"git push tag '$trimmedTag'")
    } yield ()
  }
}
