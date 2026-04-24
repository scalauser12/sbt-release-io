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

    def isRepoRoot(path: Path): Boolean =
      Files.exists(path.resolve(".git")) ||
        Files.isRegularFile(path.resolve(".scalafmt.conf"))

    def loop(current: Path): Option[Path] =
      Option(current).flatMap { path =>
        val candidate = path.resolve(relativePath).normalize()

        if (isRepoRoot(path) && Files.exists(candidate)) Some(candidate)
        else loop(path.getParent)
      }

    loop(start).getOrElse {
      throw new IllegalArgumentException(
        s"Could not locate repository root from '$start' for relative path '$relativePath'"
      )
    }
  }
}
