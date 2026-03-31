import scala.collection.JavaConverters.*
import scala.sys.process.*
import java.util.jar.JarFile
import sbt.Keys.*
import sbt.PackageOption

lazy val core = (project in file("core"))
  .settings(
    name      := "core",
    scalaVersion := "2.12.18",
    publishTo := Some(Resolver.file("test-repo", baseDirectory.value / "target" / "test-repo"))
  )

lazy val api = (project in file("api"))
  .dependsOn(core)
  .settings(
    name      := "api",
    scalaVersion := "2.12.18",
    publishTo := Some(Resolver.file("test-repo", baseDirectory.value / "target" / "test-repo"))
  )

val checkPublishedMetadata = taskKey[Unit]("Check manifest metadata on published jars")
val checkSessionCleaned    = taskKey[Unit]("Check release metadata is cleaned from the sbt session")

def packageManifestEntries(options: Seq[PackageOption]): Map[String, String] =
  options.flatMap {
    case product: Product if product.productPrefix == "ManifestAttributes" =>
      product.productElement(0) match {
        case entries: Seq[?] @unchecked =>
          entries.collect { case (name, value: String) =>
            name.toString -> value
          }
        case _                         => Seq.empty
      }
    case _                                                       => Seq.empty
  }.toMap

def jarManifestEntries(jar: File): Map[String, String] = {
  val jarFile = new JarFile(jar)
  try
    jarFile.getManifest.getMainAttributes.entrySet().asScala.map { entry =>
      entry.getKey.toString -> entry.getValue.toString
    }.toMap
  finally jarFile.close()
}

def publishedBinaryJar(repo: File, moduleName: String): File = {
  val jars = (repo ** "*.jar").get().filter { file =>
    file.isFile &&
    !file.getName.contains("-sources") &&
    !file.getName.contains("-javadoc")
  }
  val matching = jars.filter(_.getName.startsWith(moduleName))
  assert(matching.size == 1, s"Expected one published binary jar for $moduleName but found: ${matching.mkString(", ")}")
  matching.head
}

lazy val root = (project in file("."))
  .aggregate(core, api)
  .enablePlugins(MonorepoReleasePlugin)
  .settings(
    name                          := "manifest-metadata-monorepo",
    scalaVersion                  := "2.12.18",
    releaseIOMonorepoEnablePush   := false,
    releaseIOIgnoreUntrackedFiles := true,
    checkPublishedMetadata        := {
      val coreReleaseHash = "git rev-parse core/v0.1.0^{commit}".!!.trim
      val apiReleaseHash  = "git rev-parse api/v0.1.0^{commit}".!!.trim
      assert(
        coreReleaseHash == apiReleaseHash,
        s"Expected project tags to point at the same release commit but got core=$coreReleaseHash api=$apiReleaseHash"
      )

      val expectedTags = Map(
        "core" -> "core/v0.1.0",
        "api"  -> "api/v0.1.0"
      )

      expectedTags.foreach { case (projectName, expectedTag) =>
        val jar      = publishedBinaryJar(file(projectName) / "target" / "test-repo", projectName)
        val manifest = jarManifestEntries(jar)

        assert(
          manifest.get("Vcs-Release-Hash").contains(coreReleaseHash),
          s"Expected Vcs-Release-Hash=$coreReleaseHash in ${jar.getAbsolutePath} but got ${manifest.get("Vcs-Release-Hash")}"
        )
        assert(
          manifest.get("Vcs-Release-Tag").contains(expectedTag),
          s"Expected Vcs-Release-Tag=$expectedTag in ${jar.getAbsolutePath} but got ${manifest.get("Vcs-Release-Tag")}"
        )
      }
    },
    checkSessionCleaned           := {
      val coreEntries = packageManifestEntries((LocalProject("core") / packageOptions).value)
      val apiEntries  = packageManifestEntries((LocalProject("api") / packageOptions).value)

      List(
        "core" -> coreEntries,
        "api"  -> apiEntries
      ).foreach { case (projectName, entries) =>
        assert(
          !entries.contains("Vcs-Release-Hash"),
          s"Expected $projectName packageOptions to be cleaned, but found Vcs-Release-Hash=${entries("Vcs-Release-Hash")}"
        )
        assert(
          !entries.contains("Vcs-Release-Tag"),
          s"Expected $projectName packageOptions to be cleaned, but found Vcs-Release-Tag=${entries("Vcs-Release-Tag")}"
        )
      }
    }
  )
