import scala.collection.JavaConverters.*
import scala.sys.process.*
import java.util.jar.JarFile
import sbt.PackageOption

lazy val root = (project in file("."))
  .aggregate(libA, libB)
  .settings(
    name                             := "publish-multi-project-manifest-test",
    scalaVersion                     := "2.12.18",
    publishTo                        := Some(Resolver.file("test-repo", baseDirectory.value / "target" / "test-repo")),
    releaseIOVcsIgnoreUntrackedFiles := true,
    releaseIOPolicyEnablePush        := false,
    releaseIOPolicyEnableRunClean    := false,
    releaseIOPolicyEnableRunTests    := false
  )

lazy val libA = (project in file("libA"))
  .settings(
    scalaVersion := "2.12.18",
    publishTo    := Some(Resolver.file("test-repo", baseDirectory.value / "target" / "test-repo"))
  )

lazy val libB = (project in file("libB"))
  .settings(
    scalaVersion := "2.12.18",
    publishTo    := Some(Resolver.file("test-repo", baseDirectory.value / "target" / "test-repo"))
  )

def packageManifestEntries(options: Seq[PackageOption]): Map[String, String] =
  options.flatMap {
    case product: Product if product.productPrefix == "ManifestAttributes" =>
      product.productElement(0) match {
        case entries: Seq[?] @unchecked =>
          entries.collect { case (name, value: String) =>
            name.toString -> value
          }
        case _                          => Seq.empty
      }
    case _                                                                 => Seq.empty
  }.toMap

def jarManifestEntries(jar: File): Map[String, String] = {
  val jarFile = new JarFile(jar)
  try
    jarFile.getManifest.getMainAttributes
      .entrySet()
      .asScala
      .map { entry =>
        entry.getKey.toString -> entry.getValue.toString
      }
      .toMap
  finally jarFile.close()
}

def publishedBinaryJar(repo: File, moduleName: String): File = {
  val jars         = (repo ** "*.jar").get().filter { file =>
    file.isFile &&
    !file.getName.contains("-sources") &&
    !file.getName.contains("-javadoc")
  }
  val moduleLower  = moduleName.toLowerCase
  val matching     = jars.filter(_.getName.toLowerCase.startsWith(moduleLower))
  assert(
    matching.size == 1,
    s"Expected one published binary jar for $moduleName under ${repo.getAbsolutePath} but " +
      s"found: ${matching.mkString(", ")}"
  )
  matching.head
}

val checkChildManifests = taskKey[Unit](
  "Verify aggregated child JARs carry Vcs-Release-Hash and Vcs-Release-Tag in MANIFEST.MF"
)
checkChildManifests := {
  val releaseHash = "git rev-parse v0.1.0^{commit}".!!.trim
  val expectedTag = "v0.1.0"

  // libA and libB publish to their own per-project test-repo dirs (the publishTo above
  // is project-scoped via baseDirectory.value).
  Seq("libA", "libB").foreach { module =>
    val repo     = file(module) / "target" / "test-repo"
    val jar      = publishedBinaryJar(repo, module)
    val manifest = jarManifestEntries(jar)

    assert(
      manifest.get("Vcs-Release-Hash").contains(releaseHash),
      s"Expected Vcs-Release-Hash=$releaseHash in ${jar.getAbsolutePath} but got " +
        s"${manifest.get("Vcs-Release-Hash")}"
    )
    assert(
      manifest.get("Vcs-Release-Tag").contains(expectedTag),
      s"Expected Vcs-Release-Tag=$expectedTag in ${jar.getAbsolutePath} but got " +
        s"${manifest.get("Vcs-Release-Tag")}"
    )
  }
}

val checkSessionCleaned = taskKey[Unit](
  "Verify per-project packageOptions are cleaned of release manifest entries after release"
)
checkSessionCleaned := {
  val libAEntries = packageManifestEntries((LocalProject("libA") / Keys.packageOptions).value)
  val libBEntries = packageManifestEntries((LocalProject("libB") / Keys.packageOptions).value)

  Seq("libA" -> libAEntries, "libB" -> libBEntries).foreach { case (module, entries) =>
    assert(
      !entries.contains("Vcs-Release-Hash"),
      s"Expected $module packageOptions to be cleaned, found Vcs-Release-Hash=${entries.get("Vcs-Release-Hash")}"
    )
    assert(
      !entries.contains("Vcs-Release-Tag"),
      s"Expected $module packageOptions to be cleaned, found Vcs-Release-Tag=${entries.get("Vcs-Release-Tag")}"
    )
  }
}
