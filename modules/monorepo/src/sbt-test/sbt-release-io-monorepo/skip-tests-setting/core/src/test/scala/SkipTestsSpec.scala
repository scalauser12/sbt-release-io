import java.nio.charset.StandardCharsets
import java.nio.file.Files

import munit.FunSuite

class SkipTestsSpec extends FunSuite {
  test("writes a marker when tests run") {
    val base   = java.nio.file.Paths.get(sys.props("project.base"))
    val marker = base.resolve("marker").resolve("tests-ran")
    Files.createDirectories(marker.getParent)
    Files.write(marker, "tests ran".getBytes(StandardCharsets.UTF_8))
    assert(true)
  }
}
