package io.release.monorepo

import sbt.ProjectRef

import java.io.File
import java.nio.file.Files
import java.util.concurrent.atomic.AtomicLong

object MonorepoTestSupport {

  private val dummyBuildRoot = {
    val dir = Files.createTempDirectory("sbt-release-io-monorepo-dummy-build").toFile
    dir.deleteOnExit()
    dir
  }

  private val nextDummyProjectId = new AtomicLong(0L)

  def dummyProject(name: String): ProjectReleaseInfo = {
    val projectId   = nextDummyProjectId.incrementAndGet()
    val baseDir     = new File(dummyBuildRoot, s"$name-$projectId")
    val versionFile = new File(baseDir, "version.sbt")

    baseDir.mkdirs()
    baseDir.deleteOnExit()
    versionFile.deleteOnExit()

    ProjectReleaseInfo(
      ref = ProjectRef(dummyBuildRoot.toURI, name),
      name = name,
      baseDir = baseDir,
      versionFile = versionFile
    )
  }
}
