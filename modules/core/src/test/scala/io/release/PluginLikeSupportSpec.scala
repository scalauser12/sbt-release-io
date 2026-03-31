package io.release

import munit.FunSuite

// PluginLikeSupport has protected methods, so the spec class itself must extend
// the trait. That makes every test body a call-site inside the subclass, which
// satisfies Scala's protected-access rule without any forwarding wrappers.
class PluginLikeSupportSpec
    extends FunSuite
    with PluginLikeSupport[PluginLikeSupportSpec.Step, String] {

  import PluginLikeSupportSpec.Step

  override protected def stepName(step: Step): String = step.name

  private val stepA    = Step("stepA")
  private val stepB    = Step("stepB")
  private val stepC    = Step("stepC")
  private val defaults = Seq(stepA, stepB, stepC)

  // ── findStepIndex ──────────────────────────────────────────────────────────

  test("findStepIndex - returns the correct index for the first step") {
    assertEquals(findStepIndex(defaults, "stepA"), 0)
  }

  test("findStepIndex - returns the correct index for a middle step") {
    assertEquals(findStepIndex(defaults, "stepB"), 1)
  }

  test("findStepIndex - returns the correct index for the last step") {
    assertEquals(findStepIndex(defaults, "stepC"), 2)
  }

  test("findStepIndex - throws IllegalArgumentException when the step is not found") {
    intercept[IllegalArgumentException] {
      findStepIndex(defaults, "missing")
    }
  }

  test("findStepIndex - error message includes the missing step name") {
    val err = intercept[IllegalArgumentException] {
      findStepIndex(defaults, "missing")
    }
    assert(
      err.getMessage.contains("missing"),
      s"Expected message to contain 'missing': ${err.getMessage}"
    )
  }

  test("findStepIndex - error message lists all available step names") {
    val err = intercept[IllegalArgumentException] {
      findStepIndex(defaults, "missing")
    }
    assert(err.getMessage.contains("stepA"), clue(err.getMessage))
    assert(err.getMessage.contains("stepB"), clue(err.getMessage))
    assert(err.getMessage.contains("stepC"), clue(err.getMessage))
  }

  test("findStepIndex - throws IllegalArgumentException when defaults is empty") {
    intercept[IllegalArgumentException] {
      findStepIndex(Seq.empty[Step], "stepA")
    }
  }

  // ── insertAfter ───────────────────────────────────────────────────────────

  test("insertAfter - inserts extra steps immediately after the named step") {
    val extra  = Seq((_: String) => Step("extra1"), (_: String) => Step("extra2"))
    val result = insertAfter(defaults, "stepA")(extra)

    assertEquals(
      result.map(fn => fn("resource").name),
      Seq("stepA", "extra1", "extra2", "stepB", "stepC")
    )
  }

  test("insertAfter - inserts extra steps after the last step") {
    val extra  = Seq((_: String) => Step("extra"))
    val result = insertAfter(defaults, "stepC")(extra)

    assertEquals(
      result.map(fn => fn("resource").name),
      Seq("stepA", "stepB", "stepC", "extra")
    )
  }

  test("insertAfter - inserts extra steps after a middle step") {
    val extra  = Seq((_: String) => Step("extra"))
    val result = insertAfter(defaults, "stepB")(extra)

    assertEquals(
      result.map(fn => fn("resource").name),
      Seq("stepA", "stepB", "extra", "stepC")
    )
  }

  test("insertAfter - total result length equals defaults size plus extra steps size") {
    val extra  = Seq((_: String) => Step("extra"))
    val result = insertAfter(defaults, "stepA")(extra)

    assertEquals(result.size, defaults.size + extra.size)
  }

  test("insertAfter - throws IllegalArgumentException for an unknown step name") {
    val extra = Seq((_: String) => Step("extra"))
    intercept[IllegalArgumentException] {
      insertAfter(defaults, "unknown")(extra)
    }
  }

  test("insertAfter - preserves all original steps when the extra sequence is empty") {
    val result = insertAfter(defaults, "stepB")(Seq.empty)

    assertEquals(
      result.map(fn => fn("resource").name),
      Seq("stepA", "stepB", "stepC")
    )
  }

  // ── insertBefore ──────────────────────────────────────────────────────────

  test("insertBefore - inserts extra steps immediately before the named step") {
    val extra  = Seq((_: String) => Step("extra1"), (_: String) => Step("extra2"))
    val result = insertBefore(defaults, "stepC")(extra)

    assertEquals(
      result.map(fn => fn("resource").name),
      Seq("stepA", "stepB", "extra1", "extra2", "stepC")
    )
  }

  test("insertBefore - inserts extra steps before the first step") {
    val extra  = Seq((_: String) => Step("extra"))
    val result = insertBefore(defaults, "stepA")(extra)

    assertEquals(
      result.map(fn => fn("resource").name),
      Seq("extra", "stepA", "stepB", "stepC")
    )
  }

  test("insertBefore - inserts extra steps before a middle step") {
    val extra  = Seq((_: String) => Step("extra"))
    val result = insertBefore(defaults, "stepB")(extra)

    assertEquals(
      result.map(fn => fn("resource").name),
      Seq("stepA", "extra", "stepB", "stepC")
    )
  }

  test("insertBefore - total result length equals defaults size plus extra steps size") {
    val extra  = Seq((_: String) => Step("extra"))
    val result = insertBefore(defaults, "stepC")(extra)

    assertEquals(result.size, defaults.size + extra.size)
  }

  test("insertBefore - throws IllegalArgumentException for an unknown step name") {
    val extra = Seq((_: String) => Step("extra"))
    intercept[IllegalArgumentException] {
      insertBefore(defaults, "unknown")(extra)
    }
  }

  test("insertBefore - preserves all original steps when the extra sequence is empty") {
    val result = insertBefore(defaults, "stepB")(Seq.empty)

    assertEquals(
      result.map(fn => fn("resource").name),
      Seq("stepA", "stepB", "stepC")
    )
  }

  // ── liftStep ──────────────────────────────────────────────────────────────

  test("liftStep - the lifted function returns the original step regardless of the resource") {
    val lifted = liftStep(stepA)

    assertEquals(lifted("resource1"), stepA)
    assertEquals(lifted("resource2"), stepA)
    assertEquals(lifted(""), stepA)
  }

  test("liftStep - the lifted function ignores the resource value") {
    val step   = Step("myStep")
    val lifted = liftStep(step)

    assertEquals(lifted("any-resource").name, "myStep")
  }

  test("liftStep - lifting distinct steps produces functions that return distinct steps") {
    val liftedA = liftStep(stepA)
    val liftedB = liftStep(stepB)

    assertNotEquals(liftedA("r"), liftedB("r"))
  }

  // ── liftSteps ─────────────────────────────────────────────────────────────

  test("liftSteps - maps liftStep over the full sequence preserving order") {
    val lifted = liftSteps(defaults)

    assertEquals(lifted.size, defaults.size)
    assertEquals(lifted.map(fn => fn("resource")), defaults)
  }

  test("liftSteps - returns an empty sequence when given an empty sequence") {
    assertEquals(liftSteps(Seq.empty[Step]), Seq.empty[String => Step])
  }

  test("liftSteps - each lifted function independently returns its own step") {
    val lifted = liftSteps(defaults)

    assertEquals(lifted.map(fn => fn("any")), defaults)
  }

  test("liftSteps - lifted functions all ignore the resource value") {
    val lifted = liftSteps(defaults)

    assertEquals(lifted.map(fn => fn("r1")), lifted.map(fn => fn("r2")))
  }
}

object PluginLikeSupportSpec {
  case class Step(name: String)
}
