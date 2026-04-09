package io.release.runtime.workflow

import cats.effect.IO

import java.io.File
import java.nio.charset.StandardCharsets
import java.nio.file.Files

/** Shared blocking helpers for release version-file validation and writes. */
private[release] object VersionFileSupport {

  def ensureExists(
      versionFile: File,
      notFoundMessage: => String
  ): IO[Unit] =
    IO.blocking(versionFile.exists()).flatMap { exists =>
      if (exists) IO.unit
      else IO.raiseError(new IllegalStateException(notFoundMessage))
    }

  def writeUtf8(
      versionFile: File,
      contents: String
  ): IO[Unit] =
    IO.blocking {
      Files.write(versionFile.toPath, contents.getBytes(StandardCharsets.UTF_8))
      ()
    }
}
