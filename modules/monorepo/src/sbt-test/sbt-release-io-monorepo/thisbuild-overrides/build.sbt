// Regression: ensure ThisBuild-scoped monorepo settings flow through to
// project-scope lookups via sbt's delegation. If the plugin installs its
// defaults at project scope (the way it used to), these `ThisBuild / ...`
// overrides would be silently shadowed because project scope wins over
// ThisBuild on the project axis.
ThisBuild / releaseIOMonorepoPolicyEnablePush         := false
ThisBuild / releaseIOMonorepoPolicyEnableTagging      := false
ThisBuild / releaseIOMonorepoPolicyEnableRunTests     := false
ThisBuild / releaseIOMonorepoPolicyEnableRunClean     := false
ThisBuild / releaseIOMonorepoBehaviorSkipPublish      := true
ThisBuild / releaseIOMonorepoBehaviorInteractive      := true
ThisBuild / releaseIOMonorepoBehaviorCrossBuild       := true
ThisBuild / releaseIOMonorepoPublishChecks            := false
ThisBuild / releaseIOMonorepoDetectionEnabled           := false
ThisBuild / releaseIOMonorepoDetectionIncludeDownstream := true

lazy val core = (project in file("core"))
  .settings(name := "core", scalaVersion := "2.12.18")

lazy val root = (project in file("."))
  .aggregate(core)
  .enablePlugins(MonorepoReleasePlugin)
  .settings(
    name                             := "thisbuild-overrides-monorepo",
    releaseIOVcsIgnoreUntrackedFiles := true,
    checkThisBuildOverrides          := {
      assert(
        !releaseIOMonorepoPolicyEnablePush.value,
        "ThisBuild releaseIOMonorepoPolicyEnablePush := false was shadowed by plugin default"
      )
      assert(
        !releaseIOMonorepoPolicyEnableTagging.value,
        "ThisBuild releaseIOMonorepoPolicyEnableTagging := false was shadowed by plugin default"
      )
      assert(
        !releaseIOMonorepoPolicyEnableRunTests.value,
        "ThisBuild releaseIOMonorepoPolicyEnableRunTests := false was shadowed by plugin default"
      )
      assert(
        !releaseIOMonorepoPolicyEnableRunClean.value,
        "ThisBuild releaseIOMonorepoPolicyEnableRunClean := false was shadowed by plugin default"
      )
      assert(
        releaseIOMonorepoBehaviorSkipPublish.value,
        "ThisBuild releaseIOMonorepoBehaviorSkipPublish := true was shadowed by plugin default"
      )
      assert(
        releaseIOMonorepoBehaviorInteractive.value,
        "ThisBuild releaseIOMonorepoBehaviorInteractive := true was shadowed by plugin default"
      )
      assert(
        releaseIOMonorepoBehaviorCrossBuild.value,
        "ThisBuild releaseIOMonorepoBehaviorCrossBuild := true was shadowed by plugin default"
      )
      assert(
        !releaseIOMonorepoPublishChecks.value,
        "ThisBuild releaseIOMonorepoPublishChecks := false was shadowed by plugin default"
      )
      assert(
        !releaseIOMonorepoDetectionEnabled.value,
        "ThisBuild releaseIOMonorepoDetectionEnabled := false was shadowed by plugin default"
      )
      assert(
        releaseIOMonorepoDetectionIncludeDownstream.value,
        "ThisBuild releaseIOMonorepoDetectionIncludeDownstream := true was shadowed by plugin default"
      )
    }
  )

val checkThisBuildOverrides =
  taskKey[Unit]("Assert ThisBuild-scoped monorepo overrides take effect at project scope")
