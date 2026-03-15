import scala.sys.process.*

name         := "custom-version-format-test"
scalaVersion := "2.12.18"

// Point releaseIOVersionFile to a properties file instead of the default version.sbt
releaseIOVersionFile := baseDirectory.value / "version.properties"

// Custom reader: parse app.version=x.y.z from properties file
releaseIOReadVersion := { (f: File) =>
  _root_.cats.effect.IO.blocking(sbt.IO.read(f)).flatMap { contents =>
    val pattern = """app\.version=(.+)""".r
    pattern.findFirstMatchIn(contents) match {
      case Some(m) => _root_.cats.effect.IO.pure(m.group(1).trim)
      case None    =>
        _root_.cats.effect.IO.raiseError(
          new RuntimeException(
            s"Could not parse version from ${f.getName}. Expected format: app.version=x.y.z"
          )
        )
    }
  }
}

// Custom writer: read existing file, replace only the app.version line, preserve everything else
releaseIOVersionFileContents := { (f: File, ver: String) =>
  _root_.cats.effect.IO.blocking(sbt.IO.read(f)).map { contents =>
    contents.linesIterator
      .map {
        case line if line.startsWith("app.version=") => s"app.version=$ver"
        case line                                    => line
      }
      .mkString("\n") + "\n"
  }
}

releaseIOIgnoreUntrackedFiles := true

releaseIOProcess := releaseIOProcess.value.filterNot { step =>
  step.name == "push-changes" || step.name == "publish-artifacts"
}

val checkVersionProps =
  taskKey[Unit]("Verify version.properties has custom format with next version")
checkVersionProps := {
  val content = sbt.IO.read(baseDirectory.value / "version.properties")
  assert(
    content.contains("app.version=0.2.0-SNAPSHOT"),
    s"Expected version.properties to contain 'app.version=0.2.0-SNAPSHOT', but got:\n$content"
  )
  assert(
    !content.contains("version :="),
    s"Expected version.properties to NOT contain sbt format 'version :=', but got:\n$content"
  )
  assert(
    content.contains("app.name=my-app"),
    s"Expected version.properties to preserve 'app.name=my-app', but got:\n$content"
  )
}

val checkReleaseVersionCommit =
  taskKey[Unit]("Verify the release version commit has correct version.properties")
checkReleaseVersionCommit := {
  val content = "git show v0.1.0:version.properties".!!
  assert(
    content.contains("app.version=0.1.0"),
    s"Expected release commit to contain 'app.version=0.1.0', but got:\n$content"
  )
  assert(
    content.contains("app.name=my-app"),
    s"Expected release commit to preserve 'app.name=my-app', but got:\n$content"
  )
  assert(
    !content.contains("-SNAPSHOT"),
    s"Expected release commit to NOT contain '-SNAPSHOT', but got:\n$content"
  )
}
