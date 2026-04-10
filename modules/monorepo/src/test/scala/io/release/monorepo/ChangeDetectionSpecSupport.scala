package io.release.monorepo

import cats.effect.IO
import cats.effect.Resource
import io.release.TestSupport
import io.release.monorepo.internal.*
import io.release.vcs.Vcs
import sbt.ProjectRef
import sbt.State

import java.io.ByteArrayOutputStream
import java.io.File

trait ChangeDetectionSpecSupport {

  protected final class TestEnv(
      val state: State,
      val consoleBuffer: ByteArrayOutputStream
  )

  protected val perProjectTagName: (String, String) => String =
    (name, version) => s"$name-v$version"

  protected val repoResource: Resource[IO, File] =
    TestSupport.tempDirResource("change-detection-spec")

  protected val outsideDirResource: Resource[IO, File] =
    TestSupport.tempDirResource("change-detection-outside")

  protected def projectInfo(
      repo: File,
      name: String,
      baseDir: File,
      versionFile: File
  ): ProjectReleaseInfo =
    ProjectReleaseInfo(
      ref = ProjectRef(repo.toURI, name),
      name = name,
      baseDir = baseDir,
      versionFile = versionFile
    )

  protected def rootProject(repo: File): ProjectReleaseInfo =
    projectInfo(repo, "root", repo, new File(repo, "version.sbt"))

  protected def nestedProject(repo: File, name: String): ProjectReleaseInfo =
    projectInfo(repo, name, new File(repo, name), new File(repo, s"$name/version.sbt"))

  protected def detectChanged(
      vcs: Vcs,
      projects: Seq[ProjectReleaseInfo],
      state: State,
      sharedPaths: Seq[String] = Seq.empty,
      additionalExcludeFiles: Seq[File] = Seq.empty,
      tagNameFn: (String, String) => String = perProjectTagName
  ): IO[Seq[ProjectReleaseInfo]] =
    ChangeDetection.detectChangedProjects(
      vcs,
      projects,
      tagNameFn,
      state,
      additionalExcludeFiles = additionalExcludeFiles,
      sharedPaths = sharedPaths
    )

  protected def detectVcs(repo: File): IO[Vcs] =
    Vcs.detect(repo).flatMap {
      case Some(vcs) => IO.pure(vcs)
      case None      =>
        IO.raiseError(
          new RuntimeException(s"Failed to detect VCS in ${repo.getAbsolutePath}")
        )
    }

  protected def testEnv(baseDir: File, logFileName: String = "sbt-test.log"): TestEnv = {
    val buffered = TestSupport.bufferedState(baseDir, new File(baseDir, logFileName))
    new TestEnv(buffered.state, buffered.consoleBuffer)
  }

  protected def readLogs(
      env: TestEnv,
      required: Seq[String] = Nil
  ): IO[String] = IO.blocking {
    val logs    = env.consoleBuffer.toString("UTF-8")
    val missing = required.filterNot(logs.contains)
    assert(missing.isEmpty, s"Missing expected log(s): ${missing.mkString(", ")}\n$logs")
    logs
  }
}
