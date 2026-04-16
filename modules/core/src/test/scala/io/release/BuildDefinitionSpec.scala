package io.release

import munit.FunSuite

class BuildDefinitionSpec extends FunSuite {

  test("build definition keeps testkit on internal test configurations") {
    val buildSbt               = TestRepoFiles.readString("build.sbt")
    val internalTestkitPattern = """testkit\s*%\s*"test-internal->compile"""".r
    val leakedTestkitPattern   = """testkit\s*%\s*"test->compile"""".r

    assert(internalTestkitPattern.findFirstIn(buildSbt).nonEmpty)
    assertEquals(leakedTestkitPattern.findFirstIn(buildSbt), None)
  }
}
