import munit.FunSuite

class FailSpec extends FunSuite {
  test("fail") {
    fail("This test is designed to fail")
  }
}
