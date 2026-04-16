ThisBuild / versionScheme := Some("early-semver")

lazy val testkit = (project in file("modules/testkit"))
  .enablePlugins(SbtPlugin)
  .settings(
    BuildSettings.commonSettings,
    name                                   := "sbt-release-io-testkit",
    description                            := "Internal shared test harness for sbt-release-io",
    publish / skip                         := true,
    libraryDependencies += "org.scalameta" %% "munit" % BuildVersions.munitVersion
  )

lazy val runtime = (project in file("modules/runtime"))
  .enablePlugins(SbtPlugin)
  .settings(
    BuildSettings.commonSettings,
    name           := "sbt-release-io-runtime",
    description    := "Internal shared runtime/kernel for sbt-release-io modules",
    publish / skip := true
  )

lazy val core: Project = (project in file("modules/core"))
  .enablePlugins(SbtPlugin)
  .dependsOn(
    runtime % "compile-internal->compile;test-internal->test",
    testkit % "test->compile"
  )
  .settings(
    BuildSettings.commonSettings,
    BuildSettings.scriptedModuleSettings("monorepo"),
    name                                   := "sbt-release-io",
    description                            := "A cats-effect IO port of sbt-release for sbt",
    libraryDependencies += "org.scalameta" %% "parsers" % BuildVersions.scalametaParsersVersion % Test,
    Compile / packageBin / mappings ++= RuntimePackagingCompat.classMappings(runtime).value,
    Compile / packageSrc / mappings ++= RuntimePackagingCompat.sourceMappings(runtime).value
  )

lazy val monorepo: Project = (project in file("modules/monorepo"))
  .enablePlugins(SbtPlugin)
  .dependsOn(
    core,
    runtime % "compile-internal->compile;test-internal->test",
    testkit % "test->compile"
  )
  .settings(
    BuildSettings.commonSettings,
    BuildSettings.scriptedModuleSettings("core"),
    name                 := "sbt-release-io-monorepo",
    description          := "Monorepo extension for sbt-release-io"
  )

lazy val allModules = Seq(testkit, runtime, core, monorepo)
lazy val allModuleRefs = allModules.map(project => LocalProject(project.id))

lazy val root = (project in file("."))
  .aggregate(allModuleRefs*)
  .settings(
    name           := "sbt-release-io-root",
    publish / skip := true
  )

Global / excludeLintKeys ++= Set(
  ThisBuild / git.gitUncommittedChanges,
  root / git.gitDescribedVersion
) ++ allModuleRefs.map(_ / git.gitDescribedVersion)

// Some test suites redirect System.in or exercise shared sbt state, so keep
// unit-test execution deterministic across aggregated projects as well.
Global / concurrentRestrictions += Tags.limit(Tags.Test, 1)
