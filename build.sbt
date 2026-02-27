lazy val commonSettings = Seq(
  organization       := "io.github.sbt-release-io",
  libraryDependencies ++= Seq(
    "org.typelevel" %% "cats-effect"                % "3.6.3",
    "org.specs2"    %% "specs2-core"                % "4.20.4" % Test,
    "org.typelevel" %% "cats-effect-testing-specs2" % "1.7.0"  % Test
  ),
  scalacOptions ++= Seq(
    "-deprecation",
    "-feature",
    "-unchecked"
  ),
  scriptedLaunchOpts := {
    scriptedLaunchOpts.value ++
      Seq("-Xmx1024M", "-Dplugin.version=" + version.value)
  },
  scriptedBufferLog  := false
)

lazy val core = (project in file("modules/core"))
  .enablePlugins(SbtPlugin)
  .settings(
    commonSettings,
    name := "sbt-release-io",
    addSbtPlugin("com.github.sbt" % "sbt-release" % "1.4.0")
  )

lazy val monorepo = (project in file("modules/monorepo"))
  .enablePlugins(SbtPlugin)
  .dependsOn(core)
  .settings(
    commonSettings,
    name := "sbt-release-io-monorepo"
  )

lazy val root = (project in file("."))
  .aggregate(core, monorepo)
  .settings(
    name           := "sbt-release-io-root",
    publish / skip := true
  )
