package com.example

class FailingTest extends munit.FunSuite {
  sys.error("run-tests should be disabled by releaseIOPolicyEnableRunTests := false")
}
