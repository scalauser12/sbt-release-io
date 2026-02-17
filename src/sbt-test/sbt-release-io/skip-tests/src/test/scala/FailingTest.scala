class FailingTest extends org.scalatest.funsuite.AnyFunSuite {
  test("this test fails") {
    fail("This test always fails")
  }
}
