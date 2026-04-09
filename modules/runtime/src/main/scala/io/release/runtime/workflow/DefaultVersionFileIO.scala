package io.release.runtime.workflow

import cats.effect.IO

import java.io.File

/** Shared default version-file parsing and rendering used by both release modes. */
private[release] object DefaultVersionFileIO {

  private val versionPattern = """(?:ThisBuild\s*/\s*)?version\s*:=\s*"([^"]+)"""".r

  /** Default version file reader. Parses `[ThisBuild /] version := "x.y.z"`.
    * Skips comment lines to avoid matching commented-out versions.
    */
  val defaultReadVersion: File => IO[String] = { file =>
    for {
      contents <- IO.blocking(sbt.IO.read(file))
      result   <- IO.fromOption {
                    contents
                      .replaceAll("""(?s)/\*.*?\*/""", "")
                      .linesIterator
                      .map(_.trim)
                      .filterNot(_.startsWith("//"))
                      .flatMap(versionPattern.findFirstMatchIn(_).map(_.group(1)))
                      .buffered
                      .headOption
                  }(
                    new IllegalStateException(
                      s"Could not parse version from ${file.getName}. " +
                        s"""Expected format: [ThisBuild /] version := "x.y.z"\nContents:\n$contents\n""" +
                        "If you use a custom version file format, configure " +
                        "`releaseIOVersioningFile`, `releaseIOVersioningReadVersion`, and " +
                        "`releaseIOVersioningFileContents`. See `releaseIO help` for examples."
                    )
                  )
    } yield result
  }

  /** Default version file writer. Produces `[ThisBuild /] version := "x.y.z"`. */
  def defaultWriteVersion(useGlobalVersion: Boolean): (File, String) => IO[String] =
    (_, ver) => {
      val key = if (useGlobalVersion) "ThisBuild / version" else "version"
      IO.pure(s"""$key := "$ver"\n""")
    }
}
