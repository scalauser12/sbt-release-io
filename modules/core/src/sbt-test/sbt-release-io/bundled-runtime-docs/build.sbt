import scala.collection.JavaConverters.*
import java.nio.file.Files
import java.nio.file.Path
import java.util.jar.JarFile

name := "bundled-runtime-docs-test"

scalaVersion := "2.12.18"

releaseIOPolicyEnablePublish := false
releaseIOPolicyEnablePush    := false

releaseIOVcsIgnoreUntrackedFiles := true

val checkBundledRuntimeDocs =
  taskKey[Unit]("Check that the published local core javadoc jar contains bundled runtime docs")

def ivyHome: File =
  sys.props.get("sbt.ivy.home").map(file).getOrElse(file(sys.props("user.home")) / ".ivy2")

def findCoreJavadocJars(root: Path, pluginVersion: String): List[File] =
  if (!Files.exists(root)) Nil
  else {
    val stream = Files.walk(root)

    try
      stream.iterator().asScala.toList.flatMap { path =>
        val isVersionDocsDir =
          Files.isDirectory(path) &&
            path.getFileName.toString == "docs" &&
            Option(path.getParent).exists(_.getFileName.toString == pluginVersion)

        if (!isVersionDocsDir) Nil
        else {
          val docsStream = Files.list(path)

          try
            docsStream.iterator().asScala
              .filter(Files.isRegularFile(_))
              .map(_.toFile)
              .filter(_.getName.endsWith("-javadoc.jar"))
              .filter(_.getName.startsWith("sbt-release-io"))
              .filterNot(_.getName.contains("monorepo"))
              .toList
          finally docsStream.close()
        }
      }
    finally stream.close()
  }

def pickCoreJavadocJar(jars: List[File]): Option[File] = {
  val sorted = jars.sortBy(_.getName)

  sorted.find(_.getName.startsWith("sbt-release-io_"))
    .orElse(sorted.find(_.getName == "sbt-release-io-javadoc.jar"))
}

checkBundledRuntimeDocs := {
  val log           = streams.value.log
  val pluginVersion = sys.props.getOrElse("plugin.version", sys.error("plugin.version not set"))
  val localRoot     = ivyHome.toPath.resolve("local").resolve("io.github.scalauser12")
  val candidates    = findCoreJavadocJars(localRoot, pluginVersion)
  val javadocJar    =
    pickCoreJavadocJar(candidates).getOrElse {
      sys.error(
        s"Could not locate core javadoc jar for plugin version $pluginVersion under ${localRoot.toAbsolutePath}. Found candidates: ${candidates.mkString(", ")}"
      )
    }

  log.info(s"Checking bundled runtime docs in ${javadocJar.getAbsolutePath}")

  val jarFile = new JarFile(javadocJar)

  try {
    val entries  = jarFile.entries().asScala.map(_.getName).toSet
    val required = Set(
      "io/release/vcs/Vcs.html",
      "io/release/version/Version.html",
      "io/release/CleanCompat$.html"
    )
    val missing  = required.diff(entries)

    assert(
      missing.isEmpty,
      s"Expected bundled runtime docs ${required.toList.sorted.mkString(", ")} in ${javadocJar.getName}, missing: ${missing.toList.sorted.mkString(", ")}"
    )
  } finally jarFile.close()
}
