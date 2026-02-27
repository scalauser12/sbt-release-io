package io.release.monorepo

import cats.effect.IO
import org.specs2.mutable.Specification

class MonorepoStepDefSpec extends Specification {

  "MonorepoReleaseIO factory methods" should {

    val io = new MonorepoReleaseIO {}

    "globalStep creates a Global step" in {
      val step = io.globalStep("test-global") { ctx =>
        IO.pure(ctx.withAttr("key", "value"))
      }

      (step.name must_== "test-global") and
        (step must beAnInstanceOf[MonorepoStepIO.Global])
    }

    "perProjectStep creates a PerProject step" in {
      val step = io.perProjectStep("test-per-project") { (ctx, project) =>
        IO.pure(ctx.withAttr("project", project.name))
      }

      (step.name must_== "test-per-project") and
        (step must beAnInstanceOf[MonorepoStepIO.PerProject])
    }
  }
}
