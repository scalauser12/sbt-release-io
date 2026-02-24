class ExampleTest extends org.scalatest.funsuite.AnyFunSuite {
  test("hello returns greeting") {
    assert(Example.hello == "Hello, World!")
  }
}
