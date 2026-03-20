package com.example

/** Fails during suite init (same behavior as the previous ScalaTest version). */
class FailingTest extends munit.FunSuite {
  sys.error("This test always fails during initialization")
}
