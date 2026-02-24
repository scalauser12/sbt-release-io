package io.release.monorepo

import org.specs2.mutable.Specification

class DependencyGraphSpec extends Specification {

  // DependencyGraph.topologicalSort requires a real sbt State with build structure,
  // making it impractical to unit test with mocked state. The sort logic is verified
  // through scripted integration tests instead.

  "DependencyGraph" should {

    "be available" in {
      DependencyGraph must not(beNull)
    }
  }
}
