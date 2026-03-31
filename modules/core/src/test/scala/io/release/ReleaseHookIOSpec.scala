package io.release

import cats.effect.IO
import cats.effect.Ref
import munit.CatsEffectSuite

class ReleaseHookIOSpec extends CatsEffectSuite {

  private val fixturePrefix = "release-hook-io-spec"

  // ---------------------------------------------------------------------------
  // ReleaseHookIO.io
  // ---------------------------------------------------------------------------

  test("ReleaseHookIO.io - assigns the given name to the hook") {
    val hook = ReleaseHookIO.io("my-io-hook")(_ => IO.raiseError(new RuntimeException("unused")))
    assertEquals(hook.name, "my-io-hook")
  }

  test("ReleaseHookIO.io - execute delegates to the supplied function") {
    TestSupport.dummyContextResource(fixturePrefix).use { ctx =>
      val modified = ctx.withVersions("1.0.0", "1.1.0-SNAPSHOT")
      val hook     = ReleaseHookIO.io("transform-hook")(_ => IO.pure(modified))
      hook
        .execute(ctx)
        .map(result => assertEquals(result.versions, Some(("1.0.0", "1.1.0-SNAPSHOT"))))
    }
  }

  test("ReleaseHookIO.io - execute receives the original context") {
    TestSupport.dummyContextResource(fixturePrefix).use { ctx =>
      val hook = ReleaseHookIO.io("echo-hook")(c => IO.pure(c))
      hook.execute(ctx).map(result => assertEquals(result, ctx))
    }
  }

  test("ReleaseHookIO.io - execute propagates IO errors") {
    TestSupport.dummyContextResource(fixturePrefix).use { ctx =>
      val hook =
        ReleaseHookIO.io("failing-hook")(_ => IO.raiseError(new RuntimeException("hook-boom")))
      hook.execute(ctx).attempt.map {
        case Left(e: RuntimeException) => assert(e.getMessage.contains("hook-boom"))
        case other                     => fail(s"Expected RuntimeException but got $other")
      }
    }
  }

  test("ReleaseHookIO.io - default validate is a no-op that returns unit") {
    TestSupport.dummyContextResource(fixturePrefix).use { ctx =>
      val hook = ReleaseHookIO.io("io-hook-default-validate")(_ => IO.pure(ctx))
      hook.validate(ctx).map(result => assertEquals(result, ()))
    }
  }

  // ---------------------------------------------------------------------------
  // ReleaseHookIO.action
  // ---------------------------------------------------------------------------

  test("ReleaseHookIO.action - assigns the given name to the hook") {
    val hook = ReleaseHookIO.action("my-action-hook")(_ => IO.unit)
    assertEquals(hook.name, "my-action-hook")
  }

  test(
    "ReleaseHookIO.action - execute runs the effect and returns the original context unchanged"
  ) {
    TestSupport.dummyContextResource(fixturePrefix).use { ctx =>
      Ref.of[IO, Boolean](false).flatMap { sideEffect =>
        val hook = ReleaseHookIO.action("side-effect-hook")(_ => sideEffect.set(true))
        hook.execute(ctx).flatMap { result =>
          sideEffect.get.map { ran =>
            assertEquals(result, ctx)
            assert(ran)
          }
        }
      }
    }
  }

  test("ReleaseHookIO.action - execute receives the original context when running the effect") {
    TestSupport.dummyContextResource(fixturePrefix).use { ctx =>
      Ref.of[IO, Option[ReleaseContext]](None).flatMap { captured =>
        val hook = ReleaseHookIO.action("capture-hook")(c => captured.set(Some(c)))
        hook.execute(ctx).flatMap { _ =>
          captured.get.map(observed => assertEquals(observed, Some(ctx)))
        }
      }
    }
  }

  test("ReleaseHookIO.action - execute propagates IO errors from the effect") {
    TestSupport.dummyContextResource(fixturePrefix).use { ctx =>
      val hook = ReleaseHookIO
        .action("failing-action")(_ => IO.raiseError(new RuntimeException("action-boom")))
      hook.execute(ctx).attempt.map {
        case Left(e: RuntimeException) => assert(e.getMessage.contains("action-boom"))
        case other                     => fail(s"Expected RuntimeException but got $other")
      }
    }
  }

  test("ReleaseHookIO.action - default validate is a no-op that returns unit") {
    TestSupport.dummyContextResource(fixturePrefix).use { ctx =>
      val hook = ReleaseHookIO.action("action-hook-default-validate")(_ => IO.unit)
      hook.validate(ctx).map(result => assertEquals(result, ()))
    }
  }

  // ---------------------------------------------------------------------------
  // ReleaseResourceHookIO.io
  // ---------------------------------------------------------------------------

  test("ReleaseResourceHookIO.io - assigns the given name to the hook") {
    val hook = ReleaseResourceHookIO.io[String]("my-resource-io-hook")(_ => ctx => IO.pure(ctx))
    assertEquals(hook.name, "my-resource-io-hook")
  }

  test("ReleaseResourceHookIO.io - execute(resource)(ctx) delegates to the supplied function") {
    TestSupport.dummyContextResource(fixturePrefix).use { ctx =>
      val modified = ctx.withVersions("2.0.0", "2.1.0-SNAPSHOT")
      val hook     =
        ReleaseResourceHookIO.io[String]("resource-transform-hook")(_ => _ => IO.pure(modified))
      hook
        .execute("any-resource")(ctx)
        .map(result => assertEquals(result.versions, Some(("2.0.0", "2.1.0-SNAPSHOT"))))
    }
  }

  test("ReleaseResourceHookIO.io - execute passes the resource value to the function") {
    TestSupport.dummyContextResource(fixturePrefix).use { ctx =>
      Ref.of[IO, Option[String]](None).flatMap { captured =>
        val hook = ReleaseResourceHookIO
          .io[String]("capture-resource-hook")(r => c => captured.set(Some(r)).as(c))
        hook.execute("my-resource")(ctx).flatMap { _ =>
          captured.get.map(observed => assertEquals(observed, Some("my-resource")))
        }
      }
    }
  }

  test("ReleaseResourceHookIO.io - execute propagates IO errors") {
    TestSupport.dummyContextResource(fixturePrefix).use { ctx =>
      val hook = ReleaseResourceHookIO.io[String]("failing-resource-hook")(_ =>
        _ => IO.raiseError(new RuntimeException("resource-boom"))
      )
      hook.execute("r")(ctx).attempt.map {
        case Left(e: RuntimeException) => assert(e.getMessage.contains("resource-boom"))
        case other                     => fail(s"Expected RuntimeException but got $other")
      }
    }
  }

  test("ReleaseResourceHookIO.io - default validate is a no-op that returns unit") {
    TestSupport.dummyContextResource(fixturePrefix).use { ctx =>
      val hook =
        ReleaseResourceHookIO.io[String]("resource-io-hook-default-validate")(_ => c => IO.pure(c))
      hook.validate(ctx).map(result => assertEquals(result, ()))
    }
  }

  // ---------------------------------------------------------------------------
  // ReleaseResourceHookIO.action
  // ---------------------------------------------------------------------------

  test("ReleaseResourceHookIO.action - assigns the given name to the hook") {
    val hook = ReleaseResourceHookIO.action[Int]("my-resource-action-hook")(_ => _ => IO.unit)
    assertEquals(hook.name, "my-resource-action-hook")
  }

  test(
    "ReleaseResourceHookIO.action - execute(resource)(ctx) runs the effect and returns ctx unchanged"
  ) {
    TestSupport.dummyContextResource(fixturePrefix).use { ctx =>
      Ref.of[IO, Boolean](false).flatMap { sideEffect =>
        val hook = ReleaseResourceHookIO
          .action[Int]("resource-side-effect-hook")(_ => _ => sideEffect.set(true))
        hook.execute(42)(ctx).flatMap { result =>
          sideEffect.get.map { ran =>
            assertEquals(result, ctx)
            assert(ran)
          }
        }
      }
    }
  }

  test("ReleaseResourceHookIO.action - execute passes the resource value to the effect") {
    TestSupport.dummyContextResource(fixturePrefix).use { ctx =>
      Ref.of[IO, Option[Int]](None).flatMap { captured =>
        val hook = ReleaseResourceHookIO
          .action[Int]("capture-resource-action-hook")(r => _ => captured.set(Some(r)))
        hook.execute(99)(ctx).flatMap { _ =>
          captured.get.map(observed => assertEquals(observed, Some(99)))
        }
      }
    }
  }

  test("ReleaseResourceHookIO.action - execute propagates IO errors from the effect") {
    TestSupport.dummyContextResource(fixturePrefix).use { ctx =>
      val hook = ReleaseResourceHookIO.action[String]("failing-resource-action")(_ =>
        _ => IO.raiseError(new RuntimeException("resource-action-boom"))
      )
      hook.execute("r")(ctx).attempt.map {
        case Left(e: RuntimeException) => assert(e.getMessage.contains("resource-action-boom"))
        case other                     => fail(s"Expected RuntimeException but got $other")
      }
    }
  }

  test("ReleaseResourceHookIO.action - default validate is a no-op that returns unit") {
    TestSupport.dummyContextResource(fixturePrefix).use { ctx =>
      val hook = ReleaseResourceHookIO
        .action[String]("resource-action-hook-default-validate")(_ => _ => IO.unit)
      hook.validate(ctx).map(result => assertEquals(result, ()))
    }
  }
}
