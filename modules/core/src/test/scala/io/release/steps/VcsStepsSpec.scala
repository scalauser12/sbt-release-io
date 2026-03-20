package io.release.steps

import cats.effect.{IO, Resource}
import io.release.{ReleaseContext, TestSupport}
import munit.CatsEffectSuite

import java.io.File
import java.nio.file.Files

class VcsStepsSpec extends CatsEffectSuite {

  test("pushChanges.validate - pass with a broken tracking remote when upstream is configured") {
    releaseContextResource.use { ctx =>
      VcsSteps.pushChanges.validate(ctx).map { result =>
        assertEquals(result, ())
      }
    }
  }

  test("pushChanges.execute - fail during remote preflight in non-interactive mode") {
    releaseContextResource.use { ctx =>
      VcsSteps.pushChanges.execute(ctx).attempt.map {
        case Left(err: IllegalStateException) =>
          assert(err.getMessage.contains("Aborting the release due to remote check failure."))
        case other                            =>
          fail(s"Expected IllegalStateException but got $other")
      }
    }
  }

  private val tempDirResource: Resource[IO, File] =
    Resource.make(IO.blocking(Files.createTempDirectory("vcs-steps-spec").toFile))(dir =>
      IO.blocking(TestSupport.deleteRecursively(dir))
    )

  private val releaseContextResource: Resource[IO, ReleaseContext] =
    tempDirResource.evalMap { repo =>
      TestSupport.initRepoWithBrokenRemote(repo).map { vcs =>
        ReleaseContext(
          state = TestSupport.dummyState(repo),
          vcs = Some(vcs),
          interactive = false
        )
      }
    }
}
