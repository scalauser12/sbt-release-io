package io.release.monorepo

import cats.effect.IO
import org.specs2.mutable.Specification
import sbt.AttributeKey

class MonorepoStepDefSpec extends Specification {

  "MonorepoReleaseIO factory methods" should {

    val io = new MonorepoReleaseIO {}

    "globalStep creates a Global step" in {
      val key  = AttributeKey[String]("key")
      val step = io.globalStep("test-global") { ctx =>
        IO.pure(ctx.withMetadata(key, "value"))
      }

      (step.name must_== "test-global") and
        (step must beAnInstanceOf[MonorepoStepIO.Global])
    }

    "perProjectStep creates a PerProject step" in {
      val key  = AttributeKey[String]("project")
      val step = io.perProjectStep("test-per-project") { (ctx, project) =>
        IO.pure(ctx.withMetadata(key, project.name))
      }

      (step.name must_== "test-per-project") and
        (step must beAnInstanceOf[MonorepoStepIO.PerProject])
    }

    "globalStepAction creates a Global step that passes context through" in {
      val step = io.globalStepAction("test-action") { ctx =>
        IO.unit
      }

      (step.name must_== "test-action") and
        (step must beAnInstanceOf[MonorepoStepIO.Global])
    }

    "perProjectStepAction creates a PerProject step that passes context through" in {
      val step = io.perProjectStepAction("test-pp-action") { (ctx, project) =>
        IO.unit
      }

      (step.name must_== "test-pp-action") and
        (step must beAnInstanceOf[MonorepoStepIO.PerProject])
    }

    "perProjectStepAction supports enableCrossBuild" in {
      val step = io.perProjectStepAction("cross-action", enableCrossBuild = true) { (ctx, _) =>
        IO.unit
      }

      step.asInstanceOf[MonorepoStepIO.PerProject].enableCrossBuild must beTrue
    }
  }

  "MonorepoReleaseIO insert helpers" should {

    val io = new MonorepoReleaseIO {}

    val steps = Seq(
      MonorepoStepIO.Global("step-a", ctx => IO.pure(ctx)),
      MonorepoStepIO.Global("step-b", ctx => IO.pure(ctx)),
      MonorepoStepIO.Global("step-c", ctx => IO.pure(ctx))
    )

    val extra = Seq(MonorepoStepIO.Global("extra", ctx => IO.pure(ctx)))

    "insertStepAfter inserts after the named step" in {
      val result = io.insertStepAfter(steps, "step-a")(extra)
      result.map(_.name) must_== Seq("step-a", "extra", "step-b", "step-c")
    }

    "insertStepBefore inserts before the named step" in {
      val result = io.insertStepBefore(steps, "step-c")(extra)
      result.map(_.name) must_== Seq("step-a", "step-b", "extra", "step-c")
    }

    "insertStepAfter throws on missing step name" in {
      io.insertStepAfter(steps, "nonexistent")(extra) must throwA[IllegalArgumentException](
        "Step 'nonexistent' not found"
      )
    }

    "insertStepBefore throws on missing step name" in {
      io.insertStepBefore(steps, "nonexistent")(extra) must throwA[IllegalArgumentException](
        "Step 'nonexistent' not found"
      )
    }
  }
}
