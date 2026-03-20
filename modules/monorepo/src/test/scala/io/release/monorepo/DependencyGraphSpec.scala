package io.release.monorepo

import cats.effect.{IO, Resource}
import io.release.TestSupport
import munit.CatsEffectSuite
import sbt.ProjectRef

import java.nio.file.Files

class DependencyGraphSpec extends CatsEffectSuite {

  test("DependencyGraph.topologicalSort - return empty sequence for empty project list") {
    Resource
      .make(IO.blocking(Files.createTempDirectory("dep-graph-spec").toFile))(dir =>
        IO.blocking(TestSupport.deleteRecursively(dir))
      )
      .use { dir =>
        val state = TestSupport.dummyState(dir)
        DependencyGraph.topologicalSort(Seq.empty[ProjectRef], state).map { result =>
          assertEquals(result, Seq.empty)
        }
      }
  }
}
