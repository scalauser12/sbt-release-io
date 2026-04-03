val Sbt1Version            = "1.12.3"
val Sbt2Version            = "2.0.0-RC9"
val Scala212               = "2.12.21"
val Scala3                 = "3.8.1"
val MunitVersion           = "1.2.4"
val MunitCatsEffectVersion = "2.2.0"

ThisBuild / versionScheme := Some("early-semver")

lazy val commonSettings = Seq(
  organization                  := "io.github.scalauser12",
  crossScalaVersions            := Seq(Scala212, Scala3),
  pluginCrossBuild / sbtVersion := {
    scalaBinaryVersion.value match {
      case "2.12" => Sbt1Version
      case "3"    => Sbt2Version
      case other  =>
        sys.error(
          s"Unsupported Scala binary version for sbt plugin cross-build: $other"
        )
    }
  },
  homepage                      := Some(url("https://github.com/scalauser12/sbt-release-io")),
  licenses                      := List("Apache-2.0" -> url("https://www.apache.org/licenses/LICENSE-2.0")),
  developers                    := List(
    Developer(
      id = "scalauser12",
      name = "Boris Kotsev",
      email = "scalauser12@users.noreply.github.com",
      url = url("https://github.com/scalauser12")
    )
  ),
  scmInfo                       := Some(
    ScmInfo(
      url("https://github.com/scalauser12/sbt-release-io"),
      "scm:git:git@github.com:scalauser12/sbt-release-io.git"
    )
  ),
  libraryDependencies ++= Seq(
    "org.typelevel" %% "cats-effect"       % "3.7.0",
    "org.scalameta" %% "munit"             % MunitVersion           % Test,
    "org.typelevel" %% "munit-cats-effect" % MunitCatsEffectVersion % Test
  ),
  scalacOptions ++= Seq(
    "-deprecation",
    "-feature",
    "-unchecked"
  ),
  // Scala 3 `-Wunused:imports` spams false positives on `import sbt.{internal as _, *}`.
  scalacOptions ++= {
    CrossVersion.partialVersion(scalaVersion.value) match {
      case Some((2, 12)) => Seq("-Ywarn-unused-import")
      case _             => Nil
    }
  },
  scriptedLaunchOpts            := {
    scriptedLaunchOpts.value ++
      Seq("-Xmx1024M", "-Dplugin.version=" + version.value) ++
      sys.props.get("sbt.version").map(v => s"-Dsbt.version=$v").toSeq
  },
  scriptedBufferLog             := true,
  scriptedParallelInstances     := 4,
  Test / fork                   := true,
  Test / parallelExecution      := false,
  testFrameworks += new TestFramework("munit.Framework")
)

lazy val testkit = (project in file("modules/testkit"))
  .enablePlugins(SbtPlugin)
  .settings(
    commonSettings,
    name                                   := "sbt-release-io-testkit",
    description                            := "Internal shared test harness for sbt-release-io",
    publish / skip                         := true,
    libraryDependencies += "org.scalameta" %% "munit" % MunitVersion
  )

lazy val core = (project in file("modules/core"))
  .enablePlugins(SbtPlugin)
  .dependsOn(testkit % "test->compile")
  .settings(
    commonSettings,
    name        := "sbt-release-io",
    description := "A cats-effect IO port of sbt-release for sbt",
    Test / unmanagedSourceDirectories += baseDirectory.value / "examples"
  )

lazy val monorepo = (project in file("modules/monorepo"))
  .enablePlugins(SbtPlugin)
  .dependsOn(core, testkit % "test->compile")
  .settings(
    commonSettings,
    name        := "sbt-release-io-monorepo",
    description := "Monorepo extension for sbt-release-io",
    Test / unmanagedSourceDirectories += baseDirectory.value / "examples"
  )

lazy val root = (project in file("."))
  .aggregate(testkit, core, monorepo)
  .settings(
    name           := "sbt-release-io-root",
    publish / skip := true
  )

Global / excludeLintKeys ++= Set(
  ThisBuild / git.gitUncommittedChanges,
  testkit / git.gitDescribedVersion,
  core / git.gitDescribedVersion,
  monorepo / git.gitDescribedVersion,
  root / git.gitDescribedVersion
)

// Some test suites redirect System.in or exercise shared sbt state, so keep
// unit-test execution deterministic across aggregated projects as well.
Global / concurrentRestrictions += Tags.limit(Tags.Test, 1)
