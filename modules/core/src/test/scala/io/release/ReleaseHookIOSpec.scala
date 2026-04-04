package io.release

import cats.effect.IO
import cats.effect.Ref
import io.release.internal.CoreHookConfiguration
import io.release.internal.CoreHookSlots
import io.release.internal.CoreLifecycleSlots
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
    ReleaseTestSupport.dummyContextResource(fixturePrefix).use { ctx =>
      val modified = ctx.withVersions("1.0.0", "1.1.0-SNAPSHOT")
      val hook     = ReleaseHookIO.io("transform-hook")(_ => IO.pure(modified))
      hook
        .execute(ctx)
        .map(result => assertEquals(result.versions, Some(("1.0.0", "1.1.0-SNAPSHOT"))))
    }
  }

  test("ReleaseHookIO.io - execute receives the original context") {
    ReleaseTestSupport.dummyContextResource(fixturePrefix).use { ctx =>
      val hook = ReleaseHookIO.io("echo-hook")(c => IO.pure(c))
      hook.execute(ctx).map(result => assertEquals(result, ctx))
    }
  }

  test("ReleaseHookIO.io - execute propagates IO errors") {
    ReleaseTestSupport.dummyContextResource(fixturePrefix).use { ctx =>
      val hook =
        ReleaseHookIO.io("failing-hook")(_ => IO.raiseError(new RuntimeException("hook-boom")))
      hook.execute(ctx).attempt.map {
        case Left(e: RuntimeException) => assert(e.getMessage.contains("hook-boom"))
        case other                     => fail(s"Expected RuntimeException but got $other")
      }
    }
  }

  test("ReleaseHookIO.io - default validate is a no-op that returns unit") {
    ReleaseTestSupport.dummyContextResource(fixturePrefix).use { ctx =>
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
    ReleaseTestSupport.dummyContextResource(fixturePrefix).use { ctx =>
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
    ReleaseTestSupport.dummyContextResource(fixturePrefix).use { ctx =>
      Ref.of[IO, Option[ReleaseContext]](None).flatMap { captured =>
        val hook = ReleaseHookIO.action("capture-hook")(c => captured.set(Some(c)))
        hook.execute(ctx).flatMap { _ =>
          captured.get.map(observed => assertEquals(observed, Some(ctx)))
        }
      }
    }
  }

  test("ReleaseHookIO.action - execute propagates IO errors from the effect") {
    ReleaseTestSupport.dummyContextResource(fixturePrefix).use { ctx =>
      val hook = ReleaseHookIO
        .action("failing-action")(_ => IO.raiseError(new RuntimeException("action-boom")))
      hook.execute(ctx).attempt.map {
        case Left(e: RuntimeException) => assert(e.getMessage.contains("action-boom"))
        case other                     => fail(s"Expected RuntimeException but got $other")
      }
    }
  }

  test("ReleaseHookIO.action - default validate is a no-op that returns unit") {
    ReleaseTestSupport.dummyContextResource(fixturePrefix).use { ctx =>
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
    ReleaseTestSupport.dummyContextResource(fixturePrefix).use { ctx =>
      val modified = ctx.withVersions("2.0.0", "2.1.0-SNAPSHOT")
      val hook     =
        ReleaseResourceHookIO.io[String]("resource-transform-hook")(_ => _ => IO.pure(modified))
      hook
        .execute("any-resource")(ctx)
        .map(result => assertEquals(result.versions, Some(("2.0.0", "2.1.0-SNAPSHOT"))))
    }
  }

  test("ReleaseResourceHookIO.io - execute passes the resource value to the function") {
    ReleaseTestSupport.dummyContextResource(fixturePrefix).use { ctx =>
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
    ReleaseTestSupport.dummyContextResource(fixturePrefix).use { ctx =>
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
    ReleaseTestSupport.dummyContextResource(fixturePrefix).use { ctx =>
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
    ReleaseTestSupport.dummyContextResource(fixturePrefix).use { ctx =>
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
    ReleaseTestSupport.dummyContextResource(fixturePrefix).use { ctx =>
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
    ReleaseTestSupport.dummyContextResource(fixturePrefix).use { ctx =>
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
    ReleaseTestSupport.dummyContextResource(fixturePrefix).use { ctx =>
      val hook = ReleaseResourceHookIO
        .action[String]("resource-action-hook-default-validate")(_ => _ => IO.unit)
      hook.validate(ctx).map(result => assertEquals(result, ()))
    }
  }

  // ---------------------------------------------------------------------------
  // ReleaseResourceHooks.materialize
  // ---------------------------------------------------------------------------

  test(
    "ReleaseResourceHooks.materialize - with Some(resource) hook calls through to resource function"
  ) {
    ReleaseTestSupport.dummyContextResource(fixturePrefix).use { ctx =>
      Ref.of[IO, List[String]](Nil).flatMap { log =>
        val resourceHook = ReleaseResourceHookIO.action[String]("before-tag") { resource => _ =>
          log.update(_ :+ resource)
        }
        val hooks        = ReleaseResourceHooks[String](beforeTagHooks = Seq(resourceHook))
        val config       = ReleaseResourceHooks.materialize(hooks, Some("my-resource"))

        config.beforeTagHooks.head.execute(ctx).flatMap { result =>
          log.get.map { events =>
            assertEquals(events, List("my-resource"))
            assertEquals(result, ctx)
          }
        }
      }
    }
  }

  test("ReleaseResourceHooks.materialize - materializes after-clean-check hooks") {
    ReleaseTestSupport.dummyContextResource(fixturePrefix).use { ctx =>
      Ref.of[IO, List[String]](Nil).flatMap { log =>
        val resourceHook = ReleaseResourceHookIO.action[String]("after-clean-check") {
          resource => _ =>
            log.update(_ :+ resource)
        }
        val hooks        = ReleaseResourceHooks[String](afterCleanCheckHooks = Seq(resourceHook))
        val config       = ReleaseResourceHooks.materialize(hooks, Some("my-resource"))

        config.afterCleanCheckHooks.head.execute(ctx).flatMap { result =>
          log.get.map { events =>
            assertEquals(events, List("my-resource"))
            assertEquals(result, ctx)
          }
        }
      }
    }
  }

  test(
    "ReleaseResourceHooks.materialize - with None hook returns context unchanged without calling resource function"
  ) {
    ReleaseTestSupport.dummyContextResource(fixturePrefix).use { ctx =>
      Ref.of[IO, List[String]](Nil).flatMap { log =>
        val resourceHook = ReleaseResourceHookIO.action[String]("before-tag") { _ => _ =>
          log.update(_ :+ "should-not-run")
        }
        val hooks        = ReleaseResourceHooks[String](beforeTagHooks = Seq(resourceHook))
        val config       = ReleaseResourceHooks.materialize(hooks, None)

        config.beforeTagHooks.head.execute(ctx).flatMap { result =>
          log.get.map { events =>
            assertEquals(events, Nil)
            assertEquals(result, ctx)
          }
        }
      }
    }
  }

  test("ReleaseResourceHooks.materialize - preserves hook names during materialization") {
    ReleaseTestSupport.dummyContextResource(fixturePrefix).use { _ =>
      val beforeTagHook  = ReleaseResourceHookIO.io[String]("named-before-tag-hook")(_ => IO.pure)
      val afterCleanHook =
        ReleaseResourceHookIO.io[String]("named-after-clean-hook")(_ => IO.pure)
      val hooks          =
        ReleaseResourceHooks[String](
          beforeTagHooks = Seq(beforeTagHook),
          afterCleanCheckHooks = Seq(afterCleanHook)
        )
      val config         = ReleaseResourceHooks.materialize(hooks, None)

      IO {
        assertEquals(config.beforeTagHooks.head.name, "named-before-tag-hook")
        assertEquals(config.afterCleanCheckHooks.head.name, "named-after-clean-hook")
      }
    }
  }

  test("ReleaseResourceHooks.materialize - all boolean policies default to true") {
    ReleaseTestSupport.dummyContextResource(fixturePrefix).use { _ =>
      val config = ReleaseResourceHooks.materialize(ReleaseResourceHooks.empty[String], None)

      IO {
        assert(config.enableSnapshotDependenciesCheck)
        assert(config.enableRunClean)
        assert(config.enableRunTests)
        assert(config.enableTagging)
        assert(config.enablePublish)
        assert(config.enablePush)
      }
    }
  }

  test("ReleaseResourceHooks.materialize - empty hooks produce the neutral configuration") {
    ReleaseTestSupport.dummyContextResource(fixturePrefix).use { _ =>
      IO(
        assertEquals(
          ReleaseResourceHooks.materialize(ReleaseResourceHooks.empty[String], None),
          CoreHookConfiguration.empty
        )
      )
    }
  }

  test("ReleaseResourceHooks.materialize - populated hooks only fill the intended slot") {
    ReleaseTestSupport.dummyContextResource(fixturePrefix).use { _ =>
      val hook   = ReleaseResourceHookIO.action[String]("before-tag")(_ => _ => IO.unit)
      val config = ReleaseResourceHooks.materialize(
        ReleaseResourceHooks[String](beforeTagHooks = Seq(hook)),
        None
      )

      IO {
        val populatedSlots =
          CoreLifecycleSlots.hookSlots
            .filter(slot => slot.resolveHooks(config).nonEmpty)
            .map(_.keyLabel)
        assertEquals(populatedSlots, Seq(CoreHookSlots.beforeTagHooks.keyLabel))
        assertEquals(config.beforeTagHooks.map(_.name), Seq("before-tag"))
      }
    }
  }

  test("ReleaseResourceHooks.materialize - preserves validate function from resource hook") {
    ReleaseTestSupport.dummyContextResource(fixturePrefix).use { ctx =>
      Ref.of[IO, List[String]](Nil).flatMap { log =>
        val resourceHook = ReleaseResourceHookIO[String](
          name = "hook-with-validate",
          execute = _ => IO.pure,
          validate = _ => log.update(_ :+ "validated")
        )
        val hooks        = ReleaseResourceHooks[String](beforeTagHooks = Seq(resourceHook))
        val config       = ReleaseResourceHooks.materialize(hooks, Some("res"))

        config.beforeTagHooks.head.validate(ctx).flatMap { _ =>
          log.get.map(events => assertEquals(events, List("validated")))
        }
      }
    }
  }

  test("ReleaseResourceHooks.hookAssignments covers every hook slot exactly once") {
    ReleaseTestSupport.dummyContextResource(fixturePrefix).use { _ =>
      IO {
        val assignments =
          ReleaseResourceHooks.hookAssignments(
            ReleaseResourceHooks.empty[String],
            (resourceHook: ReleaseResourceHookIO[String]) =>
              ReleaseHookIO.action(resourceHook.name)(_ => IO.unit)
          )
        assertEquals(assignments.map(_._1.keyLabel), CoreLifecycleSlots.hookSlots.map(_.keyLabel))
      }
    }
  }
}
