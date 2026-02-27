import org.scalatest.flatspec.AnyFlatSpec

class FailTest extends AnyFlatSpec {
  "This test" should "fail" in {
    assert(false, "This test is designed to fail")
  }
}
