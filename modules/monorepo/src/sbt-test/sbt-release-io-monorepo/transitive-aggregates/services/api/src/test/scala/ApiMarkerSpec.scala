import java.nio.charset.StandardCharsets
import java.nio.file.Files
import java.nio.file.StandardOpenOption

import munit.FunSuite

class ApiMarkerSpec extends FunSuite {
  test("record api test execution") {
    val marker = java.nio.file.Paths.get(sys.props("marker.path"))
    Files.createDirectories(marker.getParent)
    Files.write(
      marker,
      "api\n".getBytes(StandardCharsets.UTF_8),
      StandardOpenOption.CREATE,
      StandardOpenOption.APPEND
    )
  }
}
