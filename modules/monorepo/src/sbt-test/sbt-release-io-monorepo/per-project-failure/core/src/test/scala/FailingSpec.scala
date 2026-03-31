import munit.FunSuite

class FailingSpec extends FunSuite {
  test("fails the core release tests") {
    fail("core tests intentionally fail")
  }
}
