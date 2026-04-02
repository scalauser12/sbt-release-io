package io.release.monorepo.steps

import io.release.monorepo.steps.MonorepoVcsCommitHelpers.commitVersions
import io.release.monorepo.{MonorepoReleaseIO as MR, *}

/** Version-related monorepo release steps: inquire, set, commit. */
@scala.annotation.nowarn("cat=deprecation")
private[monorepo] object MonorepoVersionSteps {

  /** Inquire release and next versions for each project.
    * If both versions are already resolved (for example from CLI overrides or a prior step),
    * those are used directly without prompting or computing.
    */
  val inquireVersions: MonorepoStepIO.PerProject = MonorepoStepIO.PerProject(
    name = "inquire-versions",
    validate = MonorepoVersionWorkflow.validateInquireVersions,
    execute = MonorepoVersionWorkflow.inquireVersions
  )

  /** Write release versions to per-project version files. */
  val setReleaseVersions: MonorepoStepIO.PerProject = MonorepoStepIO.PerProject(
    name = "set-release-version",
    execute = MonorepoVersionWorkflow.writeReleaseVersion
  )

  /** Write next snapshot versions to per-project version files. */
  val setNextVersions: MonorepoStepIO.PerProject = MonorepoStepIO.PerProject(
    name = "set-next-version",
    execute = MonorepoVersionWorkflow.writeNextVersion
  )

  /** Single commit for all release version files. */
  val commitReleaseVersions: MonorepoStepIO.Global = MonorepoStepIO.Global(
    name = "commit-release-versions",
    execute = ctx =>
      commitVersions(
        ctx,
        MR.releaseIOMonorepoVcsReleaseCommitMessage,
        { case (releaseVer, _) => releaseVer },
        persistReleaseHash = true
      )
  )

  /** Single commit for all next version files. */
  val commitNextVersions: MonorepoStepIO.Global = MonorepoStepIO.Global(
    name = "commit-next-versions",
    execute = ctx =>
      commitVersions(
        ctx,
        MR.releaseIOMonorepoVcsNextCommitMessage,
        { case (_, nextVer) => nextVer },
        persistReleaseHash = false
      )
  )
}
