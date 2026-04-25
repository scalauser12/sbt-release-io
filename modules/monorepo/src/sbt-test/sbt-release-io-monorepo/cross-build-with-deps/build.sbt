import _root_.io.release.monorepo.MonorepoProjectHookIO

val Scala212 = "2.12.18"
val Scala213 = "2.13.12"

lazy val core = (project in file("core"))
  .settings(
    name               := "core",
    scalaVersion       := Scala213,
    crossScalaVersions := Seq(Scala213, Scala212),
    publishTo          := Some(Resolver.file("test-repo", file("repo")))
  )

lazy val api = (project in file("api"))
  .dependsOn(core)
  .settings(
    name               := "api",
    scalaVersion       := Scala213,
    crossScalaVersions := Seq(Scala213, Scala212),
    publishTo          := Some(Resolver.file("test-repo", file("repo")))
  )

// Each per-project BeforePublish hook records, on every cross-iteration of the project,
// what every loaded project's currently-resolved `scalaVersion` is. The point of this
// test is to verify that during api's iteration at version V, core has also been
// switched to V (sbt-stock `Cross` semantics) so api's compile/publish traverses the
// dep graph at a coherent Scala version.
val recordResolvedVersionsHook =
  MonorepoProjectHookIO.action("record-resolved-versions") { (ctx, project) =>
    _root_.cats.effect.IO.blocking {
      val extracted   = sbt.Project.extract(ctx.state)
      val coreRef     =
        extracted.structure.allProjectRefs.find(_.project == "core").get
      val apiRef      =
        extracted.structure.allProjectRefs.find(_.project == "api").get
      val coreVersion = extracted.get(coreRef / scalaVersion)
      val apiVersion  = extracted.get(apiRef / scalaVersion)
      val markerDir   = project.baseDir / "marker"
      IO.createDirectory(markerDir)
      IO.append(
        markerDir / "resolved.txt",
        s"iteration-of=${project.name} core=$coreVersion api=$apiVersion\n"
      )
    }
  }

val checkAll = taskKey[Unit]("Run all verification checks")

lazy val root = (project in file("."))
  .aggregate(core, api)
  .enablePlugins(MonorepoReleasePlugin)
  .settings(
    name := "cross-build-with-deps-test",

    releaseIOMonorepoHooksBeforePublish    := Seq(recordResolvedVersionsHook),
    releaseIOVcsIgnoreUntrackedFiles       := true,
    releaseIOMonorepoPolicyEnablePush      := false,
    releaseIOMonorepoPolicyEnableRunClean  := false,
    releaseIOMonorepoPolicyEnableRunTests  := false,

    checkAll := {
      // Each project (core, api) has crossScalaVersions := [2.13, 2.12], so each one's
      // outer-loop iteration should record two BeforePublish observations.
      def readLines(p: File): List[String] =
        if (p.exists()) IO.readLines(p).filter(_.nonEmpty).toList else Nil

      val coreLines = readLines(file("core/marker/resolved.txt"))
      val apiLines  = readLines(file("api/marker/resolved.txt"))

      assert(
        coreLines.length == 2,
        s"core should have 2 BeforePublish hook invocations but had ${coreLines.length}: $coreLines"
      )
      assert(
        apiLines.length == 2,
        s"api should have 2 BeforePublish hook invocations but had ${apiLines.length}: $apiLines"
      )

      // Each iteration's recorded line shows that BOTH core and api resolved to the
      // iteration's Scala version — sbt-stock `Cross.switchVersion` aligns every project
      // whose `crossScalaVersions` contains the iteration version. Pre-stock-fix this
      // would have shown core stuck at its entry version while api's iteration moved
      // through the cross set.
      val coreAt213 = s"iteration-of=core core=$Scala213 api=$Scala213"
      val coreAt212 = s"iteration-of=core core=$Scala212 api=$Scala212"
      assert(
        coreLines.toSet == Set(coreAt213, coreAt212),
        s"core iteration should align both projects per version; got $coreLines"
      )

      val apiAt213 = s"iteration-of=api core=$Scala213 api=$Scala213"
      val apiAt212 = s"iteration-of=api core=$Scala212 api=$Scala212"
      assert(
        apiLines.toSet == Set(apiAt213, apiAt212),
        s"api iteration should align both projects per version; got $apiLines"
      )

      // Both projects published under both Scala suffixes — confirms publish actually
      // ran (and ran for the correct binary suffix on each iteration).
      def jar(name: String, scalaSuffix: String) =
        file(s"repo/$name/${name}_$scalaSuffix/0.1.0/${name}_$scalaSuffix-0.1.0.jar")
      assert(jar("core", "2.13").isFile, "missing core_2.13 publish")
      assert(jar("core", "2.12").isFile, "missing core_2.12 publish")
      assert(jar("api", "2.13").isFile, "missing api_2.13 publish")
      assert(jar("api", "2.12").isFile, "missing api_2.12 publish")
    }
  )
