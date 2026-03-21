package io.release.monorepo

import munit.FunSuite

class MonorepoTestSupportSpec extends FunSuite {

  test("dummyProject - use a real temp-backed base directory") {
    val project = MonorepoTestSupport.dummyProject("core")

    assertEquals(project.name, "core")
    assertEquals(project.ref.project, "core")
    assert(project.baseDir.isDirectory)
    assertEquals(project.versionFile.getParentFile.getCanonicalFile, project.baseDir.getCanonicalFile)
    assertEquals(project.ref.build, project.baseDir.getParentFile.toURI)
    assert(!project.versionFile.exists())
  }

  test("dummyProject - keep a stable logical ProjectRef while isolating filesystem paths") {
    val first  = MonorepoTestSupport.dummyProject("core")
    val second = MonorepoTestSupport.dummyProject("core")

    assertEquals(first.ref, second.ref)
    assertNotEquals(first.baseDir.getCanonicalPath, second.baseDir.getCanonicalPath)
  }
}
