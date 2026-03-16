import scala.sys.process.*

// 3-level hierarchy: root -> services -> api
// Default releaseIOMonorepoProjects should discover api transitively.

lazy val api = (project in file("services/api"))
  .settings(
    name                          := "api",
    scalaVersion                  := "2.12.18",
    libraryDependencies           += "org.scalatest" %% "scalatest" % "3.2.15" % Test,
    publishTo                     := Some(Resolver.file("api-test-repo", baseDirectory.value / "repo")),
    Test / testOptions            += Tests.Setup(() =>
      System.setProperty("marker.path", (baseDirectory.value / "marker" / "tests.log").getAbsolutePath)
    ),
    releaseIOPublishArtifactsAction := {
      val marker = baseDirectory.value / "marker" / "publish.log"
      IO.createDirectory(marker.getParentFile)
      IO.append(marker, "api\n")
    }
  )

lazy val services = (project in file("services"))
  .aggregate(api)
  .settings(
    name                          := "services",
    scalaVersion                  := "2.12.18",
    libraryDependencies           += "org.scalatest" %% "scalatest" % "3.2.15" % Test,
    publishTo                     := Some(
      Resolver.file("services-test-repo", baseDirectory.value / "repo")
    ),
    Test / testOptions            += Tests.Setup(() =>
      System.setProperty("marker.path", (baseDirectory.value / "marker" / "tests.log").getAbsolutePath)
    ),
    releaseIOPublishArtifactsAction := {
      val marker = baseDirectory.value / "marker" / "publish.log"
      IO.createDirectory(marker.getParentFile)
      IO.append(marker, "services\n")
    }
  )

lazy val root = (project in file("."))
  .aggregate(services)
  .enablePlugins(MonorepoReleasePlugin)
  .settings(
    name         := "transitive-agg-test",
    scalaVersion := "2.12.18",

    // Do NOT override releaseIOMonorepoProjects — use the default
    // which should transitively discover: services, api

    releaseIOMonorepoDetectChanges := false,

    releaseIOMonorepoProcess := releaseIOMonorepoProcess.value.filterNot(_.name == "push-changes"),

    releaseIOIgnoreUntrackedFiles := true,

    checkAll := {
      val tags = "git tag".!!.trim.split("\n").filter(_.nonEmpty).sorted
      // Both services and api should be released (transitively discovered)
      assert(
        tags.length == 2,
        s"Expected 2 tags but found ${tags.length}: ${tags.mkString(", ")}"
      )
      assert(
        tags.toList == List("api/v1.0.0", "services/v1.0.0"),
        s"Expected [api/v1.0.0, services/v1.0.0] but got [${tags.mkString(", ")}]"
      )

      val servicesContents = IO.read(file("services/version.sbt"))
      assert(
        servicesContents.contains("1.1.0-SNAPSHOT"),
        s"Expected services version 1.1.0-SNAPSHOT but got: $servicesContents"
      )

      val apiContents = IO.read(file("services/api/version.sbt"))
      assert(
        apiContents.contains("1.1.0-SNAPSHOT"),
        s"Expected api version 1.1.0-SNAPSHOT but got: $apiContents"
      )

      val servicesTests = IO.readLines(file("services/marker/tests.log")).filter(_.nonEmpty)
      assert(
        servicesTests == List("services"),
        s"Expected services tests to run once but got: ${servicesTests.mkString(", ")}"
      )

      val apiTests = IO.readLines(file("services/api/marker/tests.log")).filter(_.nonEmpty)
      assert(
        apiTests == List("api"),
        s"Expected api tests to run once but got: ${apiTests.mkString(", ")}"
      )

      val servicesPublishes = IO.readLines(file("services/marker/publish.log")).filter(_.nonEmpty)
      assert(
        servicesPublishes == List("services"),
        s"Expected services publish to run once but got: ${servicesPublishes.mkString(", ")}"
      )

      val apiPublishes = IO.readLines(file("services/api/marker/publish.log")).filter(_.nonEmpty)
      assert(
        apiPublishes == List("api"),
        s"Expected api publish to run once but got: ${apiPublishes.mkString(", ")}"
      )
    }
  )

val checkAll = taskKey[Unit]("Run all verification checks")
