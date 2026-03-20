package io.release.monorepo

import cats.effect.IO
import munit.FunSuite
import sbt.AttributeKey

class MonorepoStepDefSpec extends FunSuite {

  private val io = new MonorepoReleaseIO {}

  test("globalStep creates a Global step") {
    val key  = AttributeKey[String]("key")
    val step = io.globalStep("test-global") { ctx =>
      IO.pure(ctx.withMetadata(key, "value"))
    }

    assertEquals(step.name, "test-global")
    assert(step.isInstanceOf[MonorepoStepIO.Global])
  }

  test("perProjectStep creates a PerProject step") {
    val key  = AttributeKey[String]("project")
    val step = io.perProjectStep("test-per-project") { (ctx, project) =>
      IO.pure(ctx.withMetadata(key, project.name))
    }

    assertEquals(step.name, "test-per-project")
    assert(step.isInstanceOf[MonorepoStepIO.PerProject])
  }

  test("globalStepAction creates a Global step that passes context through") {
    val step = io.globalStepAction("test-action") { ctx =>
      IO.unit
    }

    assertEquals(step.name, "test-action")
    assert(step.isInstanceOf[MonorepoStepIO.Global])
  }

  test("perProjectStepAction creates a PerProject step that passes context through") {
    val step = io.perProjectStepAction("test-pp-action") { (ctx, project) =>
      IO.unit
    }

    assertEquals(step.name, "test-pp-action")
    assert(step.isInstanceOf[MonorepoStepIO.PerProject])
  }

  test("perProjectStepAction supports enableCrossBuild") {
    val step = io.perProjectStepAction("cross-action", enableCrossBuild = true) { (ctx, _) =>
      IO.unit
    }

    assert(step.asInstanceOf[MonorepoStepIO.PerProject].enableCrossBuild)
  }

  test("MonorepoStepIO.global creates a Global step via execute") {
    val step = MonorepoStepIO
      .global("my-global")
      .execute(ctx => IO.pure(ctx))

    assertEquals(step.name, "my-global")
    assert(step.isInstanceOf[MonorepoStepIO.Global])
  }

  test("MonorepoStepIO.perProject creates a PerProject step via execute") {
    val step = MonorepoStepIO
      .perProject("my-pp")
      .execute((ctx, _) => IO.pure(ctx))

    assertEquals(step.name, "my-pp")
    assert(step.isInstanceOf[MonorepoStepIO.PerProject])
  }

  test("withCrossBuild sets enableCrossBuild") {
    val step = MonorepoStepIO
      .perProject("cross-pp")
      .withCrossBuild
      .execute((ctx, _) => IO.pure(ctx))

    assert(step.asInstanceOf[MonorepoStepIO.PerProject].enableCrossBuild)
  }

  test("withValidation wires validation function on Global") {
    val step = MonorepoStepIO
      .global("validated")
      .withValidation(_ => IO.unit)
      .execute(ctx => IO.pure(ctx))

    assert(step.isInstanceOf[MonorepoStepIO.Global])
  }

  test("withValidation wires validation function on PerProject") {
    val step = MonorepoStepIO
      .perProject("validated-pp")
      .withValidation((_, _) => IO.unit)
      .execute((ctx, _) => IO.pure(ctx))

    assert(step.isInstanceOf[MonorepoStepIO.PerProject])
  }

  test("executeAction wraps IO[Unit] correctly for Global") {
    val step = MonorepoStepIO
      .global("action-global")
      .executeAction(_ => IO.unit)

    assert(step.isInstanceOf[MonorepoStepIO.Global])
  }

  test("executeAction wraps IO[Unit] correctly for PerProject") {
    val step = MonorepoStepIO
      .perProject("action-pp")
      .executeAction((_, _) => IO.unit)

    assert(step.isInstanceOf[MonorepoStepIO.PerProject])
  }

  test("withSelectionBoundary sets the flag") {
    val step = MonorepoStepIO
      .global("boundary")
      .withSelectionBoundary
      .execute(ctx => IO.pure(ctx))

    assert(step.asInstanceOf[MonorepoStepIO.Global].isSelectionBoundary)
  }

  test("globalResource produces T => MonorepoStepIO") {
    val stepFn: String => MonorepoStepIO = MonorepoStepIO
      .globalResource[String]("res-global")
      .execute(_ => ctx => IO.pure(ctx))

    val step = stepFn("test")
    assertEquals(step.name, "res-global")
    assert(step.isInstanceOf[MonorepoStepIO.Global])
  }

  test("perProjectResource produces T => MonorepoStepIO with crossBuild") {
    val stepFn: String => MonorepoStepIO = MonorepoStepIO
      .perProjectResource[String]("res-pp")
      .withCrossBuild
      .execute(_ => (ctx, _) => IO.pure(ctx))

    val step = stepFn("test")
    assertEquals(step.name, "res-pp")
    assert(step.asInstanceOf[MonorepoStepIO.PerProject].enableCrossBuild)
  }

  test("validateOnly creates a Global step with no-op execute") {
    val step = MonorepoStepIO
      .global("build-global")
      .withValidation(_ => IO.unit)
      .validateOnly

    assertEquals(step.name, "build-global")
    assert(step.isInstanceOf[MonorepoStepIO.Global])
  }

  test("validateOnly creates a PerProject step with no-op execute") {
    val step = MonorepoStepIO
      .perProject("build-pp")
      .withValidation((_, _) => IO.unit)
      .validateOnly

    assertEquals(step.name, "build-pp")
    assert(step.isInstanceOf[MonorepoStepIO.PerProject])
  }

  private val steps = Seq(
    MonorepoStepIO.Global("step-a", ctx => IO.pure(ctx)),
    MonorepoStepIO.Global("step-b", ctx => IO.pure(ctx)),
    MonorepoStepIO.Global("step-c", ctx => IO.pure(ctx))
  )

  private val extra = Seq(MonorepoStepIO.Global("extra", ctx => IO.pure(ctx)))

  test("insertStepAfter inserts after the named step") {
    val result = io.insertStepAfter(steps, "step-a")(extra)
    assertEquals(result.map(_.name), Seq("step-a", "extra", "step-b", "step-c"))
  }

  test("insertStepBefore inserts before the named step") {
    val result = io.insertStepBefore(steps, "step-c")(extra)
    assertEquals(result.map(_.name), Seq("step-a", "step-b", "extra", "step-c"))
  }

  test("insertStepAfter throws on missing step name") {
    val e = intercept[IllegalArgumentException] {
      io.insertStepAfter(steps, "nonexistent")(extra)
    }
    assert(e.getMessage.contains("Step 'nonexistent' not found"))
  }

  test("insertStepBefore throws on missing step name") {
    val e = intercept[IllegalArgumentException] {
      io.insertStepBefore(steps, "nonexistent")(extra)
    }
    assert(e.getMessage.contains("Step 'nonexistent' not found"))
  }
}
