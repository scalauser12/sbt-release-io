import sbt.Keys.*

lazy val commonSettings = Seq(
  organization := "io.github.sbt-release-io",
  sbtPlugin    := true,
  addSbtPlugin("com.github.sbt" % "sbt-release" % "1.4.0"),
  libraryDependencies ++= Seq(
    "org.typelevel" %% "cats-effect" % "3.6.3",
    "org.specs2"    %% "specs2-core" % "4.20.4" % Test
  ),
  scalacOptions ++= Seq(
    "-deprecation",
    "-feature",
    "-unchecked"
  )
)

lazy val core = (project in file("core"))
  .enablePlugins(SbtPlugin)
  .settings(
    commonSettings,
    name    := "sbt-release-io",
    version := "0.1.0-SNAPSHOT",
    scriptedLaunchOpts := {
      scriptedLaunchOpts.value ++
        Seq("-Xmx1024M", "-Dplugin.version=" + version.value)
    },
    scriptedBufferLog := false
  )

lazy val monorepo = (project in file("monorepo"))
  .enablePlugins(SbtPlugin)
  .dependsOn(core)
  .settings(
    commonSettings,
    name    := "sbt-release-io-monorepo",
    version := "0.1.0-SNAPSHOT",
    scriptedLaunchOpts := {
      scriptedLaunchOpts.value ++
        Seq("-Xmx1024M", "-Dplugin.version=" + version.value)
    },
    scriptedBufferLog := false
  )

lazy val root = (project in file("."))
  .aggregate(core, monorepo)
  .settings(
    name           := "sbt-release-io-root",
    publish / skip := true
  )
