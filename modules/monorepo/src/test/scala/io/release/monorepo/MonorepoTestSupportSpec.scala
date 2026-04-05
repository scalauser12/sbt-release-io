package io.release.monorepo

import munit.CatsEffectSuite

class MonorepoTestSupportSpec extends CatsEffectSuite with MonorepoDummyProjectSupport {

  test("dummyProject - use a real temp-backed base directory") {
    dummyProject("core").map { project =>
      assertEquals(project.name, "core")
      assertEquals(project.ref.project, "core")
      assert(project.baseDir.isDirectory)
      assertEquals(
        project.versionFile.getParentFile.getCanonicalFile,
        project.baseDir.getCanonicalFile
      )
      assertEquals(project.ref.build, project.baseDir.getParentFile.toURI)
      assert(!project.versionFile.exists())
    }
  }

  test("dummyProject - keep a stable logical ProjectRef while isolating filesystem paths") {
    for {
      first  <- dummyProject("core")
      second <- dummyProject("core")
    } yield {
      assertEquals(first.ref, second.ref)
      assertNotEquals(first.baseDir.getCanonicalPath, second.baseDir.getCanonicalPath)
    }
  }
}
