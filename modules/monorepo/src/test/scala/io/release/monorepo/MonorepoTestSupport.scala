package io.release.monorepo

import cats.effect.IO
import cats.effect.Resource
import io.release.TestSupport
import sbt.ProjectRef

import java.io.File
import java.util.concurrent.atomic.AtomicLong

object MonorepoTestSupport {

  final class DummyProjectFactory private[monorepo] (dummyBuildRoot: File) {
    private val nextDummyProjectId = new AtomicLong(0L)

    def dummyProject(name: String): IO[ProjectReleaseInfo] =
      IO.blocking {
        val projectId   = nextDummyProjectId.incrementAndGet()
        val baseDir     = new File(dummyBuildRoot, s"$name-$projectId")
        val versionFile = new File(baseDir, "version.sbt")

        if (!baseDir.mkdirs() && !baseDir.isDirectory)
          throw new IllegalStateException(
            s"Could not create dummy project directory at ${baseDir.getAbsolutePath}"
          )

        ProjectReleaseInfo(
          ref = ProjectRef(dummyBuildRoot.toURI, name),
          name = name,
          baseDir = baseDir,
          versionFile = versionFile
        )
      }
  }

  def dummyProjectFactoryResource(
      prefix: String = "sbt-release-io-monorepo-dummy-build"
  ): Resource[IO, DummyProjectFactory] =
    TestSupport.tempDirResource(prefix).map(new DummyProjectFactory(_))
}
