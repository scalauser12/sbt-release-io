package io.release.monorepo

import cats.effect.IO
import io.release.monorepo.internal.MonorepoProcessPlan
import io.release.monorepo.internal.MonorepoStepAliases.AnyStep
import io.release.monorepo.internal.steps.MonorepoReleaseSteps
import io.release.runtime.engine.BuiltInStepRole
import io.release.runtime.engine.ProcessStep
import munit.FunSuite

class MonorepoProcessPlanSpec extends FunSuite {

  private def analyze(steps: Seq[AnyStep]): MonorepoProcessPlan =
    MonorepoProcessPlan.analyze(steps).fold(error => fail(error.getMessage), identity)

  test("analyze - built-in release write chain enables future-release-commit tag preflight") {
    val plan = analyze(
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
    val plan                     = analyze(
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

  test("analyze - keep contiguous after-selection hooks in setup before main steps") {
    val afterSelectionHook = ProcessStep.Single[MonorepoContext](
      name = "after-selection:observe-selected-projects",
      execute = ctx => IO.pure(ctx)
    )
    val mainStep           = ProcessStep.Single[MonorepoContext](
      name = "custom-main-step",
      execute = ctx => IO.pure(ctx)
    )
    val plan               = analyze(
      Seq(
        MonorepoReleaseSteps.checkCleanWorkingDir,
        MonorepoReleaseSteps.detectOrSelectProjects,
        afterSelectionHook,
        mainStep
      )
    )

    assertEquals(
      plan.setupSteps.map(_.name),
      Seq(
        MonorepoReleaseSteps.checkCleanWorkingDir.name,
        MonorepoReleaseSteps.detectOrSelectProjects.name,
        "after-selection:observe-selected-projects"
      )
    )
    assertEquals(
      plan.preSelectionSetupSteps.map(_.name),
      Seq(
        MonorepoReleaseSteps.checkCleanWorkingDir.name,
        MonorepoReleaseSteps.detectOrSelectProjects.name
      )
    )
    assertEquals(
      plan.postSelectionSetupSteps.map(_.name),
      Seq("after-selection:observe-selected-projects")
    )
    assertEquals(plan.mainSteps.map(_.name), Seq("custom-main-step"))
  }

  test("analyze - reject a process without a selection boundary") {
    val result = MonorepoProcessPlan.analyze(Seq(MonorepoReleaseSteps.inquireVersions))

    result match {
      case Left(error) =>
        assert(error.getMessage.contains("exactly one selection-boundary step"))
        assert(error.getMessage.contains("found 0: none"))
      case Right(_)    => fail("Expected a missing selection boundary to be rejected")
    }
  }

  test("analyze - reject a process with duplicate selection boundaries") {
    val secondBoundary = ProcessStep.Single[MonorepoContext](
      name = "custom-selection-boundary",
      execute = ctx => IO.pure(ctx),
      roles = Set(BuiltInStepRole.SelectionBoundary)
    )
    val result         = MonorepoProcessPlan.analyze(
      Seq(MonorepoReleaseSteps.detectOrSelectProjects, secondBoundary)
    )

    result match {
      case Left(error) =>
        assert(error.getMessage.contains("exactly one selection-boundary step"))
        assert(error.getMessage.contains("found 2"))
        assert(error.getMessage.contains(MonorepoReleaseSteps.detectOrSelectProjects.name))
        assert(error.getMessage.contains(secondBoundary.name))
      case Right(_)    => fail("Expected duplicate selection boundaries to be rejected")
    }
  }
}
