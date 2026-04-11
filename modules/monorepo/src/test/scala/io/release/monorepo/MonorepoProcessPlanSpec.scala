package io.release.monorepo

import cats.effect.IO
import io.release.monorepo.internal.MonorepoProcessPlan
import io.release.monorepo.internal.steps.MonorepoReleaseSteps
import io.release.runtime.engine.ProcessStep
import munit.FunSuite

class MonorepoProcessPlanSpec extends FunSuite {

  test("analyze - built-in release write chain enables future-release-commit tag preflight") {
    val plan = MonorepoProcessPlan.analyze(
      Seq(
        MonorepoReleaseSteps.detectOrSelectProjects,
        MonorepoReleaseSteps.inquireVersions,
        MonorepoReleaseSteps.setReleaseVersions,
        MonorepoReleaseSteps.commitReleaseVersions,
        MonorepoReleaseSteps.tagReleasesPerProject
      )
    )

    assert(plan.builtInTagPreflightIncludesReleaseWriteAndCommit)
  }

  test("analyze - custom same-name step does not count as the built-in release write") {
    val customSetReleaseVersions = ProcessStep.PerItem[MonorepoContext, ProjectReleaseInfo](
      name = MonorepoReleaseSteps.setReleaseVersions.name,
      execute = (ctx, _) => IO.pure(ctx)
    )
    val plan                     = MonorepoProcessPlan.analyze(
      Seq(
        MonorepoReleaseSteps.detectOrSelectProjects,
        MonorepoReleaseSteps.inquireVersions,
        customSetReleaseVersions,
        MonorepoReleaseSteps.commitReleaseVersions,
        MonorepoReleaseSteps.tagReleasesPerProject
      )
    )

    assert(!plan.builtInTagPreflightIncludesReleaseWriteAndCommit)
  }
}
