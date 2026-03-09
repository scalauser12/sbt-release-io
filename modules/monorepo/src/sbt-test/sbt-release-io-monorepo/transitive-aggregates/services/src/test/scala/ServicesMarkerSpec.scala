import java.nio.charset.StandardCharsets
import java.nio.file.Files
import java.nio.file.StandardOpenOption

import org.scalatest.funsuite.AnyFunSuite

class ServicesMarkerSpec extends AnyFunSuite {
  test("record services test execution") {
    val marker = java.nio.file.Paths.get(sys.props("marker.path"))
    Files.createDirectories(marker.getParent)
    Files.write(
      marker,
      "services\n".getBytes(StandardCharsets.UTF_8),
      StandardOpenOption.CREATE,
      StandardOpenOption.APPEND
    )
  }
}
