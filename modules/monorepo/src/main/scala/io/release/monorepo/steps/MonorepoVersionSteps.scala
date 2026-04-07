package io.release.monorepo.steps

import io.release.internal.ProcessStep
import io.release.monorepo.MonorepoReleasePlugin
import io.release.monorepo.MonorepoStepAliases.GlobalStep
import io.release.monorepo.MonorepoStepAliases.ProjectStep
import io.release.monorepo.steps.MonorepoVcsCommitHelpers.commitVersions

/** Version-related monorepo release steps: inquire, set, commit. */
private[monorepo] object MonorepoVersionSteps {

  /** Inquire release and next versions for each project.
    * If both versions are already resolved (for example from CLI overrides or a prior step),
    * those are used directly without prompting or computing.
    */
  val inquireVersions: ProjectStep =
    ProcessStep.PerItem(
      name = "inquire-versions",
      validate = MonorepoVersionWorkflow.validateInquireVersions,
      execute = MonorepoVersionWorkflow.inquireVersions
    )

  /** Write release versions to per-project version files. */
  val setReleaseVersions: ProjectStep =
    ProcessStep.PerItem(
      name = "set-release-version",
      execute = MonorepoVersionWorkflow.writeReleaseVersion
    )

  /** Write next snapshot versions to per-project version files. */
  val setNextVersions: ProjectStep =
    ProcessStep.PerItem(
      name = "set-next-version",
      execute = MonorepoVersionWorkflow.writeNextVersion
    )

  /** Single commit for all release version files. */
  val commitReleaseVersions: GlobalStep = ProcessStep.Single(
    name = "commit-release-versions",
    execute = ctx =>
      commitVersions(
        ctx,
        MonorepoReleasePlugin.autoImport.releaseIOMonorepoVcsReleaseCommitMessage,
        { case (releaseVer, _) => releaseVer },
        persistReleaseHash = true
      )
  )

  /** Single commit for all next version files. */
  val commitNextVersions: GlobalStep = ProcessStep.Single(
    name = "commit-next-versions",
    execute = ctx =>
      commitVersions(
        ctx,
        MonorepoReleasePlugin.autoImport.releaseIOMonorepoVcsNextCommitMessage,
        { case (_, nextVer) => nextVer },
        persistReleaseHash = false
      )
  )
}
