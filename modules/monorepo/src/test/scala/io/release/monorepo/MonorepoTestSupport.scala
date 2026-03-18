package io.release.monorepo

import sbt.ProjectRef

import java.io.File
import java.net.URI

object MonorepoTestSupport {

  def dummyProject(name: String): ProjectReleaseInfo =
    ProjectReleaseInfo(
      ref = ProjectRef(new URI("file:///tmp/test"), name),
      name = name,
      baseDir = new File(s"/tmp/test/$name"),
      versionFile = new File(s"/tmp/test/$name/version.sbt")
    )
}
