package io.release.monorepo.internal.steps

import cats.effect.IO
import io.release.CleanCompat
import io.release.ReleaseIOCompat
import io.release.ReleaseSharedKeys.releaseIODiagnosticsSnapshotDependencies
import io.release.ScopedKeyLookup
import io.release.monorepo.internal.MonorepoStepAliases.ProjectStep
import io.release.monorepo.internal.steps.MonorepoStepHelpers.*
import io.release.runtime.ReleaseLogPrefixes
import io.release.runtime.engine.ProcessStep
import io.release.runtime.sbt.SnapshotDependencyTasks
import io.release.runtime.workflow.DecisionResolver
import sbt.{internal as _, *}

/** Snapshot-dependency, clean, and test step declarations. */
private[monorepo] object MonorepoVerificationSteps {

  /** Check for SNAPSHOT dependencies in each project.
    * Only checks resolved library dependencies — inter-project dependencies
    * (via `.dependsOn()`) are resolved internally by sbt from compiled classes
    * and are not included in `releaseIODiagnosticsSnapshotDependencies`.
    */
  val checkSnapshotDependencies: ProjectStep =
    ProcessStep.PerItem(
      name = "check-snapshot-dependencies",
      // Snapshot checking is purely a pre-flight check; there is no release-time action.
      execute = (ctx, _) => IO.pure(ctx),
      validateWithContext = Some((ctx, project) =>
        for {
          externalSnapshots <-
            if (
              ScopedKeyLookup.containsScopedKey(
                ctx.state,
                project.ref / releaseIODiagnosticsSnapshotDependencies
              )
            )
              SnapshotDependencyTasks.projectSnapshotDependencies(
                ctx.state,
                project.ref,
                project.name,
                releaseIODiagnosticsSnapshotDependencies
              )
            else
              SnapshotDependencyTasks.projectManagedClasspathSnapshotDependencies(
                ctx.state,
                project.ref
              )
          updatedCtx        <-
            DecisionResolver.handleSnapshotDependencies(
              ctx,
              externalSnapshots,
              ReleaseLogPrefixes.Monorepo,
              context = s" in ${project.name}"
            )
        } yield updatedCtx
      ),
      enableCrossBuild = true
    )

  /** Run clean for each project. */
  val runClean: ProjectStep = ProcessStep.PerItem(
    name = "run-clean",
    execute = (ctx, project) =>
      IO.blocking {
        val newState = CleanCompat.runBuild(ctx.state, project.ref)
        ctx.withState(newState)
      }
  )

  /** Run tests for each project. */
  val runTests: ProjectStep = ProcessStep.PerItem(
    name = "run-tests",
    execute = (ctx, project) =>
      if (ctx.skipTests)
        logInfo(ctx, s"Skipping tests for ${project.name}").as(ctx)
      else
        runProjectTask(ctx, project.ref / Test / ReleaseIOCompat.testKey),
    enableCrossBuild = true
  )

}
