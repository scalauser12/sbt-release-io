package io.release.monorepo.steps

import cats.effect.{IO, Resource}
import io.release.TestSupport
import io.release.monorepo.MonorepoContext
import munit.CatsEffectSuite

import java.io.File
import java.nio.file.Files

class MonorepoVcsStepsSpec extends CatsEffectSuite {

  test("pushChanges.validate - pass with a broken tracking remote when upstream is configured") {
    monorepoContextResource.use { ctx =>
      MonorepoVcsSteps.pushChanges.validate(ctx).map { result =>
        assertEquals(result, ())
      }
    }
  }

  test("pushChanges.execute - fail during remote preflight before any push attempt") {
    monorepoContextResource.use { ctx =>
      MonorepoVcsSteps.pushChanges.execute(ctx).attempt.map {
        case Left(err: IllegalStateException) =>
          assert(
            err.getMessage.contains("Aborting the release due to remote check failure.")
          )
        case other                            =>
          fail(s"Expected IllegalStateException but got $other")
      }
    }
  }

  private val tempDirResource: Resource[IO, File] =
    Resource.make(
      IO.blocking(Files.createTempDirectory("monorepo-vcs-steps-spec").toFile)
    )(dir => IO.blocking(TestSupport.deleteRecursively(dir)))

  private val monorepoContextResource: Resource[IO, MonorepoContext] =
    tempDirResource.evalMap { repo =>
      TestSupport.initRepoWithBrokenRemote(repo).map { vcs =>
        MonorepoContext(
          state = TestSupport.dummyState(repo),
          vcs = Some(vcs),
          interactive = false
        )
      }
    }
}
