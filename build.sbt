ThisBuild / sonatypeCredentialHost := xerial.sbt.Sonatype.sonatypeCentralHost
ThisBuild / versionScheme          := Some("early-semver")

lazy val commonSettings = Seq(
  organization              := "io.github.scalauser12",
  homepage                  := Some(url("https://github.com/scalauser12/sbt-release-io")),
  licenses                  := List("Apache-2.0" -> url("https://www.apache.org/licenses/LICENSE-2.0")),
  developers                := List(
    Developer(
      id = "scalauser12",
      name = "Boris Kotsev",
      email = "scalauser12@users.noreply.github.com",
      url = url("https://github.com/scalauser12")
    )
  ),
  scmInfo                   := Some(
    ScmInfo(
      url("https://github.com/scalauser12/sbt-release-io"),
      "scm:git@github.com:scalauser12/sbt-release-io.git"
    )
  ),
  libraryDependencies ++= Seq(
    "org.typelevel" %% "cats-effect"                % "3.6.3",
    "org.specs2"    %% "specs2-core"                % "4.23.0" % Test,
    "org.typelevel" %% "cats-effect-testing-specs2" % "1.7.0"  % Test
  ),
  scalacOptions ++= Seq(
    "-deprecation",
    "-feature",
    "-unchecked"
  ),
  scriptedLaunchOpts        := {
    scriptedLaunchOpts.value ++
      Seq("-Xmx1024M", "-Dplugin.version=" + version.value)
  },
  scriptedBufferLog         := true,
  scriptedParallelInstances := 4
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
    name                := "sbt-release-io-root",
    publish / skip      := true,
    sonatypeProfileName := "io.github.scalauser12"
  )
