// Build-wide policy/behavior overrides MUST flow through to per-project lookups.
// Before the fix, project-scoped defaults installed by `ReleasePluginIO.projectSettings`
// shadowed `ThisBuild / ...` because project scope wins over ThisBuild on the project
// axis. This test pins the ThisBuild path: set the toggles only at build scope and
// rely on sbt scope delegation to surface them at every project's lookup.
//
// Note: `releaseIOVersioningBump` is also moved to ThisBuild scope by the plugin
// default, but a user-side `ThisBuild / releaseIOVersioningBump := X` is not
// exercised here because sbt 2 caches ThisBuild task results and would demand a
// `JsonFormat[Version.Bump]` for the user expression. The plugin-internal
// default uses `Def.uncached` to bypass that requirement; user-side ThisBuild
// overrides on sbt 2 still need either project scope or `Def.uncached`. The
// scope-delegation invariant is verified below by reading the default at every
// project scope.
ThisBuild / releaseIOPolicyEnablePublish     := false
ThisBuild / releaseIOPolicyEnableRunTests    := false
ThisBuild / releaseIOPolicyEnableRunClean    := false
ThisBuild / releaseIOPolicyEnablePush        := false
ThisBuild / releaseIOBehaviorSkipPublish     := true
ThisBuild / releaseIOVcsIgnoreUntrackedFiles := true
ThisBuild / releaseIODefaultsPushAnswer      := Some(false)
ThisBuild / releaseIOPublishChecks           := false
ThisBuild / releaseIOVersioningUseGlobal     := false

lazy val root = (project in file("."))
  .aggregate(libA, libB)
  .settings(
    name         := "this-build-overrides-test",
    scalaVersion := "2.12.18"
  )

lazy val libA = (project in file("libA"))
  .settings(
    scalaVersion := "2.12.18"
  )

lazy val libB = (project in file("libB"))
  .settings(
    scalaVersion := "2.12.18"
  )

val checkBuildLevelDefaults =
  taskKey[Unit]("Verify ThisBuild overrides are visible at every project scope")
checkBuildLevelDefaults := {
  val rootPolicyPublish = (LocalProject("root") / releaseIOPolicyEnablePublish).value
  val libAPolicyPublish = (LocalProject("libA") / releaseIOPolicyEnablePublish).value
  val libBPolicyPublish = (LocalProject("libB") / releaseIOPolicyEnablePublish).value
  assert(
    !rootPolicyPublish,
    s"Expected releaseIOPolicyEnablePublish to be false on root, got $rootPolicyPublish"
  )
  assert(
    !libAPolicyPublish,
    s"Expected releaseIOPolicyEnablePublish to be false on libA, got $libAPolicyPublish"
  )
  assert(
    !libBPolicyPublish,
    s"Expected releaseIOPolicyEnablePublish to be false on libB, got $libBPolicyPublish"
  )

  val rootPolicyTests = (LocalProject("root") / releaseIOPolicyEnableRunTests).value
  val libAPolicyTests = (LocalProject("libA") / releaseIOPolicyEnableRunTests).value
  assert(!rootPolicyTests, "ThisBuild releaseIOPolicyEnableRunTests should reach root")
  assert(!libAPolicyTests, "ThisBuild releaseIOPolicyEnableRunTests should reach libA")

  val rootBehaviorSkip = (LocalProject("root") / releaseIOBehaviorSkipPublish).value
  val libBBehaviorSkip = (LocalProject("libB") / releaseIOBehaviorSkipPublish).value
  assert(rootBehaviorSkip, "ThisBuild releaseIOBehaviorSkipPublish should reach root")
  assert(libBBehaviorSkip, "ThisBuild releaseIOBehaviorSkipPublish should reach libB")

  val rootPublishChecks = (LocalProject("root") / releaseIOPublishChecks).value
  val libAPublishChecks = (LocalProject("libA") / releaseIOPublishChecks).value
  val libBPublishChecks = (LocalProject("libB") / releaseIOPublishChecks).value
  assert(!rootPublishChecks, "ThisBuild releaseIOPublishChecks override should reach root")
  assert(!libAPublishChecks, "ThisBuild releaseIOPublishChecks override should reach libA")
  assert(!libBPublishChecks, "ThisBuild releaseIOPublishChecks override should reach libB")

  val rootUseGlobal = (LocalProject("root") / releaseIOVersioningUseGlobal).value
  val libAUseGlobal = (LocalProject("libA") / releaseIOVersioningUseGlobal).value
  val libBUseGlobal = (LocalProject("libB") / releaseIOVersioningUseGlobal).value
  assert(!rootUseGlobal, "ThisBuild releaseIOVersioningUseGlobal override should reach root")
  assert(!libAUseGlobal, "ThisBuild releaseIOVersioningUseGlobal override should reach libA")
  assert(!libBUseGlobal, "ThisBuild releaseIOVersioningUseGlobal override should reach libB")

  // The plugin-installed ThisBuild default for `releaseIOVersioningBump` should
  // be readable at every project scope via sbt delegation, even though no
  // user-side override is set here (see header note for the sbt 2 limitation).
  val rootBump = (LocalProject("root") / releaseIOVersioningBump).value
  val libABump = (LocalProject("libA") / releaseIOVersioningBump).value
  val libBBump = (LocalProject("libB") / releaseIOVersioningBump).value
  assert(
    rootBump == _root_.io.release.version.Version.Bump.default,
    s"Default releaseIOVersioningBump should reach root, got $rootBump"
  )
  assert(
    libABump == _root_.io.release.version.Version.Bump.default,
    s"Default releaseIOVersioningBump should reach libA, got $libABump"
  )
  assert(
    libBBump == _root_.io.release.version.Version.Bump.default,
    s"Default releaseIOVersioningBump should reach libB, got $libBBump"
  )
}
