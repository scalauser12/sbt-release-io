package io.release.monorepo

import cats.effect.IO
import cats.effect.Ref
import io.release.TestSupport
import io.release.monorepo.internal.ChangeDetection
import munit.CatsEffectSuite

import java.io.File

class ChangeDetectionCacheSpec extends CatsEffectSuite with ChangeDetectionSpecSupport {

  test("detectChangedProjects - evict each unique tag diff after its project") {
    repoResource.use { repo =>
      for {
        _             <- initializeRepo(repo, Seq("core", "api", "util"), Seq("core", "api", "util"))
        vcs           <- detectVcs(repo)
        diffCalls     <- Ref.of[IO, Map[String, Int]](Map.empty)
        retainedDiffs <- Ref.of[IO, Vector[Int]](Vector.empty)
        projects       = Seq("core", "api", "util").map(nestedProject(repo, _))
        changed       <- detectChanged(
                           vcs,
                           projects,
                           testEnv(repo).state,
                           diffLoader = (currentVcs, tag) =>
                             diffCalls.update { calls =>
                               calls.updated(tag, calls.getOrElse(tag, 0) + 1)
                             } *> ChangeDetection.diffFilesSinceTag(currentVcs, tag),
                           retainedDiffCountObserver = count => retainedDiffs.update(_ :+ count)
                         )
        calls         <- diffCalls.get
        retained      <- retainedDiffs.get
      } yield {
        assert(changed.isEmpty)
        assertEquals(
          calls,
          Map("core-v0.1.0" -> 1, "api-v0.1.0" -> 1, "util-v0.1.0" -> 1)
        )
        assertEquals(retained, Vector(0, 0, 0))
      }
    }
  }

  test("detectChangedProjects - retain a noncontiguous shared tag until its final consumer") {
    repoResource.use { repo =>
      for {
        _             <- initializeRepo(
                           repo,
                           projectNames = Seq("core", "api", "util"),
                           tagNames = Seq("shared", "api")
                         )
        vcs           <- detectVcs(repo)
        diffCalls     <- Ref.of[IO, Map[String, Int]](Map.empty)
        retainedDiffs <- Ref.of[IO, Vector[Int]](Vector.empty)
        projects       = Seq("core", "api", "util").map(nestedProject(repo, _))
        sharedTagName  = (name: String, version: String) =>
                           if (name == "api") s"api-v$version" else s"shared-v$version"
        changed       <- detectChanged(
                           vcs,
                           projects,
                           testEnv(repo).state,
                           tagNameFn = sharedTagName,
                           diffLoader = (currentVcs, tag) =>
                             diffCalls.update { calls =>
                               calls.updated(tag, calls.getOrElse(tag, 0) + 1)
                             } *> ChangeDetection.diffFilesSinceTag(currentVcs, tag),
                           retainedDiffCountObserver = count => retainedDiffs.update(_ :+ count)
                         )
        calls         <- diffCalls.get
        retained      <- retainedDiffs.get
      } yield {
        assert(changed.isEmpty)
        assertEquals(calls, Map("shared-v0.1.0" -> 1, "api-v0.1.0" -> 1))
        assertEquals(retained, Vector(1, 1, 0))
      }
    }
  }

  private def initializeRepo(
      repo: File,
      projectNames: Seq[String],
      tagNames: Seq[String]
  ): IO[Unit] =
    IO.blocking {
      projectNames.foreach { name =>
        sbt.IO.createDirectory(new File(repo, name))
        sbt.IO.write(
          new File(repo, s"$name/version.sbt"),
          """version := "0.1.0-SNAPSHOT"""" + "\n"
        )
      }

      TestSupport.initGitRepo(repo)
      TestSupport.runGit(repo, "add", ".")
      TestSupport.runGit(repo, "commit", "-m", "Initial commit")
      tagNames.foreach(name => TestSupport.runGit(repo, "tag", s"$name-v0.1.0"))
    }
}
