def readVersionFile(path: String): String =
  IO.read(file(path)).trim

def readSbt1Version(path: String): String =
  readVersionFile(path)
    .split("\\R")
    .iterator
    .map(_.trim)
    .collectFirst {
      case line if line.startsWith("sbt.version=") =>
        line.stripPrefix("sbt.version=").trim
    }
    .filter(_.nonEmpty)
    .getOrElse(sys.error(s"Missing sbt.version entry in $path"))

val Sbt1Version             = readSbt1Version("project/build.properties")
val Sbt2Version             = readVersionFile("project/sbt2.version")
val Scala212                = "2.12.21"
val Scala3                  = "3.8.1"
val CatsEffectVersion       = "3.7.0"
val MunitVersion            = "1.2.4"
val MunitCatsEffectVersion  = "2.2.0"
val ScalametaParsersVersion = "4.13.8"

ThisBuild / versionScheme := Some("early-semver")

def forwardedScriptedJvmArgs: Seq[String] =
  Seq(
    "sbt.ivy.home",
    "sbt.boot.directory",
    "sbt.global.base",
    "sbt.repository.config",
    "sbt.override.build.repos"
  ).flatMap(key => sys.props.get(key).map(value => s"-D$key=$value"))

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
    "org.typelevel" %% "cats-effect"       % CatsEffectVersion,
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
      sys.props.get("sbt.version").map(v => s"-Dsbt.version=$v").toSeq ++
      forwardedScriptedJvmArgs
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

lazy val runtime = (project in file("modules/runtime"))
  .enablePlugins(SbtPlugin)
  .settings(
    commonSettings,
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
    commonSettings,
    name                                   := "sbt-release-io",
    description                            := "A cats-effect IO port of sbt-release for sbt",
    libraryDependencies += "org.scalameta" %% "parsers" % ScalametaParsersVersion % Test,
    Compile / packageBin / mappings ++= RuntimePackagingCompat.classMappings(runtime).value,
    Compile / packageSrc / mappings ++= RuntimePackagingCompat.sourceMappings(runtime).value,
    scriptedDependencies                   := scriptedDependencies
      .dependsOn(
        LocalProject("monorepo") / publishLocal
      )
      .value,
    Test / unmanagedSourceDirectories += baseDirectory.value / "examples"
  )

lazy val monorepo: Project = (project in file("modules/monorepo"))
  .enablePlugins(SbtPlugin)
  .dependsOn(
    core,
    runtime % "compile-internal->compile;test-internal->test",
    testkit % "test->compile"
  )
  .settings(
    commonSettings,
    name                 := "sbt-release-io-monorepo",
    description          := "Monorepo extension for sbt-release-io",
    scriptedDependencies := scriptedDependencies
      .dependsOn(
        LocalProject("core") / publishLocal
      )
      .value,
    Test / unmanagedSourceDirectories += baseDirectory.value / "examples"
  )

lazy val root = (project in file("."))
  .aggregate(testkit, runtime, core, monorepo)
  .settings(
    name           := "sbt-release-io-root",
    publish / skip := true
  )

Global / excludeLintKeys ++= Set(
  ThisBuild / git.gitUncommittedChanges,
  testkit / git.gitDescribedVersion,
  runtime / git.gitDescribedVersion,
  core / git.gitDescribedVersion,
  monorepo / git.gitDescribedVersion,
  root / git.gitDescribedVersion
)

// Some test suites redirect System.in or exercise shared sbt state, so keep
// unit-test execution deterministic across aggregated projects as well.
Global / concurrentRestrictions += Tags.limit(Tags.Test, 1)
