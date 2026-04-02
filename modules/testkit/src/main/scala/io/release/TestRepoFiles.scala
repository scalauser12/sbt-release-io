package io.release

import java.nio.file.Files
import java.nio.file.Path
import java.nio.file.Paths

/** Shared helpers for reading repository files from tests regardless of the current working
  * directory.
  */
object TestRepoFiles {

  def readString(relativePath: String): String =
    Files.readString(resolve(relativePath))

  def resolve(relativePath: String): Path = {
    val start = Paths.get(sys.props("user.dir")).toAbsolutePath.normalize()

    def loop(current: Path): Option[Path] =
      if (current == null) None
      else {
        val buildFile = current.resolve("build.sbt")
        val candidate = current.resolve(relativePath).normalize()

        if (Files.isRegularFile(buildFile) && Files.exists(candidate)) Some(candidate)
        else loop(current.getParent)
      }

    loop(start).getOrElse {
      throw new IllegalArgumentException(
        s"Could not locate repository root from '$start' for relative path '$relativePath'"
      )
    }
  }
}
