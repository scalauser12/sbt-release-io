package io.release.monorepo

import io.release.monorepo.internal.*

import munit.FunSuite

class MonorepoProjectFailuresSpec extends FunSuite {

  test("MonorepoProjectFailures - render failures with no causes and leave cause unset") {
    val error = new MonorepoProjectFailures(
      Seq(
        MonorepoProjectFailure("core", None),
        MonorepoProjectFailure("api", None)
      )
    )

    assertEquals(
      error.getMessage,
      "Per-project release failures:\n  core: failed\n  api: failed"
    )
    assert(error.getCause == null)
    assertEquals(error.getSuppressed.toSeq, Seq.empty)
  }

  test("MonorepoProjectFailures - attach the first cause and suppress the rest") {
    val first  = new RuntimeException("boom-1")
    val second = new IllegalStateException("boom-2")
    val error  = new MonorepoProjectFailures(
      Seq(
        MonorepoProjectFailure("core", Some(first)),
        MonorepoProjectFailure("api", Some(second))
      )
    )

    assertEquals(error.getCause, first)
    assertEquals(error.getSuppressed.toSeq, Seq(second))
    assertEquals(
      error.getMessage,
      "Per-project release failures:\n  core: boom-1\n  api: boom-2"
    )
  }
}
