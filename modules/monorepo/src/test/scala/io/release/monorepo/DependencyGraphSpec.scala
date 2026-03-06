package io.release.monorepo

import cats.effect.testing.specs2.CatsEffect
import cats.effect.{IO, Resource}
import io.release.TestSupport
import org.specs2.mutable.Specification
import sbt.ProjectRef

import java.nio.file.Files

class DependencyGraphSpec extends Specification with CatsEffect {

  // DependencyGraph.topologicalSort requires a real sbt State with build structure
  // for non-trivial cases. The sort logic is verified through scripted integration
  // tests (e.g., diamond-dependency). Here we verify the trivial empty-input case.

  "DependencyGraph.topologicalSort" should {

    "return empty sequence for empty project list" in {
      Resource
        .make(IO(Files.createTempDirectory("dep-graph-spec").toFile))(dir =>
          IO(TestSupport.deleteRecursively(dir))
        )
        .use { dir =>
          val state = TestSupport.dummyState(dir)
          DependencyGraph.topologicalSort(Seq.empty[ProjectRef], state).map { result =>
            result must_== Seq.empty
          }
        }
    }
  }
}
