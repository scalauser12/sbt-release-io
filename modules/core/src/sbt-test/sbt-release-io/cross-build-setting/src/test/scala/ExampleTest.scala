import munit.FunSuite

class ExampleTest extends FunSuite {
  test("hello returns greeting") {
    assertEquals(Example.hello, "Hello, World!")
  }
}
