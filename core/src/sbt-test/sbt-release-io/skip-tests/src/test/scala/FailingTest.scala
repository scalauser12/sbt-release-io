package com.example

class FailingTest extends org.scalatest.funsuite.AnyFunSuite {
  sys.error("This test always fails during initialization")
}
