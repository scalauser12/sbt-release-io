class FailingTest extends munit.FunSuite {
  test("tests should be skipped") {
    fail("run-tests should be omitted by hook policy settings")
  }
}
