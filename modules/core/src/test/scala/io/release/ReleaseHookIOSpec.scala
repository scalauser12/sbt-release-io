package io.release

import cats.effect.IO
import cats.effect.Ref
import cats.effect.Deferred
import io.release.core.internal.CoreHookConfiguration
import io.release.runtime.TrackedContextHandle
import munit.CatsEffectSuite
import sbt.AttributeKey
import scala.concurrent.duration.DurationInt

class ReleaseHookIOSpec extends CatsEffectSuite {

  private val fixturePrefix = "release-hook-io-spec"

  test("ReleaseHookIO - preserve the legacy constructor, copy, and extractor shape") {
    ReleaseTestSupport.dummyContextResource(fixturePrefix).use { ctx =>
      val hook   = ReleaseHookIO(
        name = "legacy-hook",
        execute = currentCtx => IO.pure(currentCtx.withVersions("1.0.0", "1.1.0-SNAPSHOT")),
        validate = _ => IO.unit
      )
      val copied = hook.copy(name = "copied-hook")

      copied.execute(ctx).map { result =>
        val ReleaseHookIO(name, execute, validate, _) = hook
        assertEquals(name, "legacy-hook")
        assertEquals(validate, hook.validate)
        assertEquals(execute, hook.execute)
        assertEquals(copied.name, "copied-hook")
        assertEquals(result.versions, Some(("1.0.0", "1.1.0-SNAPSHOT")))
      }
    }
  }

  test("ReleaseHookIO.ioTracked - checkpoint updates through the tracked handle") {
    ReleaseTestSupport.dummyContextResource(fixturePrefix).use { ctx =>
      val metadataKey = AttributeKey[String]("tracked-hook")
      val hook        = ReleaseHookIO.ioTracked("tracked-hook") { handle =>
        handle.update(currentCtx => IO.pure(currentCtx.withMetadata(metadataKey, "updated"))).void
      }

      TrackedContextHandle.create(ctx).flatMap { handle =>
        ReleaseHookIO.trackedExecute(hook)(handle) *> handle.get.map { result =>
          assertEquals(result.metadata(metadataKey), Some("updated"))
        }
      }
    }
  }

  test("ReleaseHookIO.ioTracked - copy preserves tracked execution") {
    ReleaseTestSupport.dummyContextResource(fixturePrefix).use { ctx =>
      val metadataKey = AttributeKey[String]("tracked-hook-copy")
      val hook        = ReleaseHookIO
        .ioTracked("tracked-hook") { handle =>
          handle.update(currentCtx => IO.pure(currentCtx.withMetadata(metadataKey, "copied"))).void
        }
        .copy(name = "tracked-hook-copy")

      TrackedContextHandle.create(ctx).flatMap { handle =>
        ReleaseHookIO.trackedExecute(hook)(handle) *> handle.get.map { result =>
          assertEquals(hook.name, "tracked-hook-copy")
          assertEquals(result.metadata(metadataKey), Some("copied"))
        }
      }
    }
  }

  test("ReleaseHookIO.ioTracked - serialize concurrent checkpoint updates") {
    ReleaseTestSupport.dummyContextResource(fixturePrefix).use { ctx =>
      val metadataKey = AttributeKey[Vector[String]]("tracked-hook-concurrent")
      val hook        = ReleaseHookIO.ioTracked("tracked-hook-concurrent") { handle =>
        def appendEntry(currentCtx: ReleaseContext, entry: String): ReleaseContext =
          currentCtx.withMetadata(
            metadataKey,
            currentCtx.metadata(metadataKey).getOrElse(Vector.empty) :+ entry
          )

        for {
          started     <- Deferred[IO, Unit]
          allowFirst  <- Deferred[IO, Unit]
          firstFiber  <- handle
                           .update(currentCtx =>
                             started.complete(()) *> allowFirst.get.as(
                               appendEntry(currentCtx, "first")
                             )
                           )
                           .start
          _           <- started.get
          secondFiber <- handle
                           .update(currentCtx => IO.pure(appendEntry(currentCtx, "second")))
                           .start
          _           <- allowFirst.complete(())
          _           <- firstFiber.joinWithNever
          _           <- secondFiber.joinWithNever
        } yield ()
      }

      TrackedContextHandle.create(ctx).flatMap { handle =>
        ReleaseHookIO.trackedExecute(hook)(handle) *> handle.get.map { result =>
          assertEquals(result.metadata(metadataKey), Some(Vector("first", "second")))
        }
      }
    }
  }

  test("ReleaseHookIO.ioTracked - nested handle access fails fast instead of deadlocking") {
    ReleaseTestSupport.dummyContextResource(fixturePrefix).use { ctx =>
      val hook = ReleaseHookIO.ioTracked("tracked-hook-nested") { handle =>
        handle
          .update(currentCtx => handle.get.as(currentCtx))
          .void
      }

      TrackedContextHandle.create(ctx).flatMap { handle =>
        for {
          result <- ReleaseHookIO.trackedExecute(hook)(handle).attempt.timeout(3.seconds)
          latest <- handle.get.timeout(3.seconds)
        } yield {
          result match {
            case Left(err: IllegalStateException) =>
              assert(err.getMessage.contains("Nested tracked-handle operations"))
            case other                            =>
              fail(s"Expected nested tracked hook access to fail fast, got $other")
          }
          assertEquals(latest, ctx)
        }
      }
    }
  }

  test("ReleaseHookIO.ioTracked - child fiber handle updates succeed after parent update returns") {
    ReleaseTestSupport.dummyContextResource(fixturePrefix).use { ctx =>
      val metadataKey = AttributeKey[Vector[String]]("tracked-hook-child-fiber")

      def appendEntry(currentCtx: ReleaseContext, entry: String): ReleaseContext =
        currentCtx.withMetadata(
          metadataKey,
          currentCtx.metadata(metadataKey).getOrElse(Vector.empty) :+ entry
        )

      val hook = ReleaseHookIO.ioTracked("tracked-hook-child-fiber") { handle =>
        for {
          childCanRun  <- Deferred[IO, Unit]
          childResult  <- Deferred[IO, Either[Throwable, Unit]]
          _            <- handle.update { currentCtx =>
                            (childCanRun.get *>
                              handle
                                .update(innerCtx => IO.pure(appendEntry(innerCtx, "child")))
                                .void).attempt
                              .flatMap(childResult.complete)
                              .start
                              .as(appendEntry(currentCtx, "parent"))
                          }
          _            <- childCanRun.complete(())
          childOutcome <- childResult.get.timeout(3.seconds)
          _            <- childOutcome match {
                            case Left(err) => IO.raiseError(err)
                            case Right(_)  => IO.unit
                          }
        } yield ()
      }

      TrackedContextHandle.create(ctx).flatMap { handle =>
        ReleaseHookIO.trackedExecute(hook)(handle) *> handle.get.map { result =>
          assertEquals(result.metadata(metadataKey), Some(Vector("parent", "child")))
        }
      }
    }
  }

  // ---------------------------------------------------------------------------
  // ReleaseHookIO.sideEffect / .transform / .resumable
  // ---------------------------------------------------------------------------

  test("ReleaseHookIO.sideEffect - runs the effect and preserves the input context") {
    ReleaseTestSupport.dummyContextResource(fixturePrefix).use { ctx =>
      Ref.of[IO, Int](0).flatMap { counter =>
        val hook = ReleaseHookIO.sideEffect("side-effect-hook")(_ => counter.update(_ + 1))

        TrackedContextHandle.create(ctx).flatMap { handle =>
          for {
            _           <- ReleaseHookIO.trackedExecute(hook)(handle)
            latest      <- handle.get
            invocations <- counter.get
          } yield {
            assertEquals(latest, ctx)
            assertEquals(invocations, 1)
          }
        }
      }
    }
  }

  test("ReleaseHookIO.transform - replaces the checkpoint with the returned context") {
    ReleaseTestSupport.dummyContextResource(fixturePrefix).use { ctx =>
      val metadataKey = AttributeKey[String]("transform-hook")
      val hook        = ReleaseHookIO
        .transform("transform-hook")(c => IO.pure(c.withMetadata(metadataKey, "applied")))

      TrackedContextHandle.create(ctx).flatMap { handle =>
        ReleaseHookIO.trackedExecute(hook)(handle) *> handle.get.map { latest =>
          assertEquals(latest.metadata(metadataKey), Some("applied"))
        }
      }
    }
  }

  test("ReleaseHookIO.resumable - exposes the tracked handle directly") {
    ReleaseTestSupport.dummyContextResource(fixturePrefix).use { ctx =>
      val metadataKey = AttributeKey[Vector[String]]("resumable-hook")
      val hook        = ReleaseHookIO.resumable("resumable-hook") { handle =>
        def append(currentCtx: ReleaseContext, entry: String): ReleaseContext =
          currentCtx.withMetadata(
            metadataKey,
            currentCtx.metadata(metadataKey).getOrElse(Vector.empty) :+ entry
          )

        handle.update(c => IO.pure(append(c, "first"))).void *>
          handle.update(c => IO.pure(append(c, "second"))).void
      }

      TrackedContextHandle.create(ctx).flatMap { handle =>
        ReleaseHookIO.trackedExecute(hook)(handle) *> handle.get.map { latest =>
          assertEquals(latest.metadata(metadataKey), Some(Vector("first", "second")))
        }
      }
    }
  }

  test("ReleaseHookIO.precondition - assigns the given name to the hook") {
    val hook = ReleaseHookIO.precondition("guard")(_ => IO.unit)
    assertEquals(hook.name, "guard")
  }

  test("ReleaseHookIO.precondition - validate runs the supplied predicate") {
    ReleaseTestSupport.dummyContextResource(fixturePrefix).use { ctx =>
      Ref.of[IO, Int](0).flatMap { invocations =>
        val hook = ReleaseHookIO.precondition("guard")(_ => invocations.update(_ + 1))
        hook.validate(ctx) *> invocations.get.map(assertEquals(_, 1))
      }
    }
  }

  test("ReleaseHookIO.precondition - validate propagates predicate failures") {
    ReleaseTestSupport.dummyContextResource(fixturePrefix).use { ctx =>
      val hook = ReleaseHookIO
        .precondition("guard-fail")(_ => IO.raiseError(new RuntimeException("guard-boom")))
      hook.validate(ctx).attempt.map {
        case Left(e: RuntimeException) => assert(e.getMessage.contains("guard-boom"))
        case other                     => fail(s"Expected RuntimeException but got $other")
      }
    }
  }

  test("ReleaseHookIO.precondition - execute is a no-op that returns ctx unchanged") {
    ReleaseTestSupport.dummyContextResource(fixturePrefix).use { ctx =>
      Ref.of[IO, Int](0).flatMap { invocations =>
        val hook = ReleaseHookIO.precondition("guard")(_ => invocations.update(_ + 1))
        hook.execute(ctx).flatMap { result =>
          invocations.get.map { ran =>
            assertEquals(result, ctx)
            assertEquals(ran, 0)
          }
        }
      }
    }
  }

  test("ReleaseResourceHookIO - preserve the legacy constructor, copy, and extractor shape") {
    ReleaseTestSupport.dummyContextResource(fixturePrefix).use { ctx =>
      val metadataKey = AttributeKey[String]("legacy-resource")
      val hook        = ReleaseResourceHookIO[String](
        name = "legacy-resource-hook",
        execute = resource => currentCtx => IO.pure(currentCtx.withMetadata(metadataKey, resource)),
        validate = _ => IO.unit
      )
      val copied      = hook.copy(name = "copied-resource-hook")

      copied.execute("bound")(ctx).map { result =>
        val ReleaseResourceHookIO(name, execute, validate, _) = hook
        assertEquals(name, "legacy-resource-hook")
        assertEquals(validate, hook.validate)
        assertEquals(execute, hook.execute)
        assertEquals(copied.name, "copied-resource-hook")
        assertEquals(result.metadata(metadataKey), Some("bound"))
      }
    }
  }

  test("ReleaseResourceHookIO.ioTracked - checkpoint resource-backed updates through the handle") {
    ReleaseTestSupport.dummyContextResource(fixturePrefix).use { ctx =>
      val metadataKey = AttributeKey[String]("tracked-resource-hook")
      val hook        = ReleaseResourceHookIO.ioTracked[String]("tracked-resource-hook") {
        resource => handle =>
          handle.update(currentCtx => IO.pure(currentCtx.withMetadata(metadataKey, resource))).void
      }

      TrackedContextHandle.create(ctx).flatMap { handle =>
        ReleaseResourceHookIO.trackedExecute(hook)("resource")(handle) *> handle.get.map { result =>
          assertEquals(result.metadata(metadataKey), Some("resource"))
        }
      }
    }
  }

  test("ReleaseResourceHookIO.ioTracked - copy preserves tracked execution") {
    ReleaseTestSupport.dummyContextResource(fixturePrefix).use { ctx =>
      val metadataKey = AttributeKey[String]("tracked-resource-hook-copy")
      val hook        = ReleaseResourceHookIO
        .ioTracked[String]("tracked-resource-hook") { resource => handle =>
          handle.update(currentCtx => IO.pure(currentCtx.withMetadata(metadataKey, resource))).void
        }
        .copy(name = "tracked-resource-hook-copy")

      TrackedContextHandle.create(ctx).flatMap { handle =>
        ReleaseResourceHookIO.trackedExecute(hook)("copied-resource")(handle) *> handle.get.map {
          result =>
            assertEquals(hook.name, "tracked-resource-hook-copy")
            assertEquals(result.metadata(metadataKey), Some("copied-resource"))
        }
      }
    }
  }

  // ---------------------------------------------------------------------------
  // ReleaseResourceHookIO.sideEffect / .transform / .resumable
  // ---------------------------------------------------------------------------

  test("ReleaseResourceHookIO.sideEffect - runs the effect and preserves the input context") {
    ReleaseTestSupport.dummyContextResource(fixturePrefix).use { ctx =>
      Ref.of[IO, Option[String]](None).flatMap { captured =>
        val hook = ReleaseResourceHookIO.sideEffect[String]("side-effect-resource-hook") {
          (resource, _) => captured.set(Some(resource))
        }

        TrackedContextHandle.create(ctx).flatMap { handle =>
          for {
            _        <- ReleaseResourceHookIO.trackedExecute(hook)("my-resource")(handle)
            latest   <- handle.get
            observed <- captured.get
          } yield {
            assertEquals(latest, ctx)
            assertEquals(observed, Some("my-resource"))
          }
        }
      }
    }
  }

  test("ReleaseResourceHookIO.transform - replaces the checkpoint with the returned context") {
    ReleaseTestSupport.dummyContextResource(fixturePrefix).use { ctx =>
      val metadataKey = AttributeKey[String]("transform-resource-hook")
      val hook        =
        ReleaseResourceHookIO.transform[String]("transform-resource-hook") { (resource, c) =>
          IO.pure(c.withMetadata(metadataKey, resource))
        }

      TrackedContextHandle.create(ctx).flatMap { handle =>
        ReleaseResourceHookIO.trackedExecute(hook)("applied")(handle) *> handle.get.map { latest =>
          assertEquals(latest.metadata(metadataKey), Some("applied"))
        }
      }
    }
  }

  test("ReleaseResourceHookIO.precondition - assigns the given name to the hook") {
    val hook = ReleaseResourceHookIO.precondition[String]("resource-guard")(_ => IO.unit)
    assertEquals(hook.name, "resource-guard")
  }

  test("ReleaseResourceHookIO.precondition - validate runs the predicate without the resource") {
    ReleaseTestSupport.dummyContextResource(fixturePrefix).use { ctx =>
      Ref.of[IO, Int](0).flatMap { invocations =>
        val hook =
          ReleaseResourceHookIO
            .precondition[String]("resource-guard")(_ => invocations.update(_ + 1))
        hook.validate(ctx) *> invocations.get.map(assertEquals(_, 1))
      }
    }
  }

  test("ReleaseResourceHookIO.precondition - validate propagates predicate failures") {
    ReleaseTestSupport.dummyContextResource(fixturePrefix).use { ctx =>
      val hook = ReleaseResourceHookIO.precondition[String]("resource-guard-fail")(_ =>
        IO.raiseError(new RuntimeException("resource-guard-boom"))
      )
      hook.validate(ctx).attempt.map {
        case Left(e: RuntimeException) => assert(e.getMessage.contains("resource-guard-boom"))
        case other                     => fail(s"Expected RuntimeException but got $other")
      }
    }
  }

  test(
    "ReleaseResourceHookIO.precondition - execute is a no-op that returns ctx unchanged without invoking the predicate"
  ) {
    ReleaseTestSupport.dummyContextResource(fixturePrefix).use { ctx =>
      Ref.of[IO, Int](0).flatMap { invocations =>
        val hook =
          ReleaseResourceHookIO
            .precondition[String]("resource-guard")(_ => invocations.update(_ + 1))
        hook.execute("any-resource")(ctx).flatMap { result =>
          invocations.get.map { ran =>
            assertEquals(result, ctx)
            assertEquals(ran, 0)
          }
        }
      }
    }
  }

  test("ReleaseResourceHookIO.resumable - exposes the tracked handle with the resource") {
    ReleaseTestSupport.dummyContextResource(fixturePrefix).use { ctx =>
      val metadataKey = AttributeKey[Vector[String]]("resumable-resource-hook")
      val hook        =
        ReleaseResourceHookIO.resumable[String]("resumable-resource-hook") { (resource, handle) =>
          def append(currentCtx: ReleaseContext, entry: String): ReleaseContext =
            currentCtx.withMetadata(
              metadataKey,
              currentCtx.metadata(metadataKey).getOrElse(Vector.empty) :+ entry
            )

          handle.update(c => IO.pure(append(c, s"$resource-first"))).void *>
            handle.update(c => IO.pure(append(c, s"$resource-second"))).void
        }

      TrackedContextHandle.create(ctx).flatMap { handle =>
        ReleaseResourceHookIO.trackedExecute(hook)("res")(handle) *> handle.get.map { latest =>
          assertEquals(
            latest.metadata(metadataKey),
            Some(Vector("res-first", "res-second"))
          )
        }
      }
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
        val resourceHook = ReleaseResourceHookIO.sideEffect[String]("before-tag") { (resource, _) =>
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
        val resourceHook = ReleaseResourceHookIO.sideEffect[String]("after-clean-check") {
          (resource, _) =>
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
        val resourceHook = ReleaseResourceHookIO.sideEffect[String]("before-tag") { (_, _) =>
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
      val beforeTagHook  =
        ReleaseResourceHookIO.transform[String]("named-before-tag-hook")((_, c) => IO.pure(c))
      val afterCleanHook =
        ReleaseResourceHookIO.transform[String]("named-after-clean-hook")((_, c) => IO.pure(c))
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
      val hook   = ReleaseResourceHookIO.sideEffect[String]("before-tag")((_, _) => IO.unit)
      val config = ReleaseResourceHooks.materialize(
        ReleaseResourceHooks[String](beforeTagHooks = Seq(hook)),
        None
      )

      IO {
        assertEquals(config.beforeTagHooks.map(_.name), Seq("before-tag"))
        // All other hook fields should be empty
        assertEquals(config.afterCleanCheckHooks, Seq.empty)
        assertEquals(config.beforeVersionResolutionHooks, Seq.empty)
        assertEquals(config.afterVersionResolutionHooks, Seq.empty)
        assertEquals(config.beforePublishHooks, Seq.empty)
        assertEquals(config.afterPublishHooks, Seq.empty)
        assertEquals(config.beforePushHooks, Seq.empty)
        assertEquals(config.afterPushHooks, Seq.empty)
      }
    }
  }

  test(
    "ReleaseResourceHooks.materialize - forwards mayChangeTagSettings onto materialized hooks"
  ) {
    // Without this forwarding, custom plugins that wire a resource hook into one of the
    // tag-affecting phases cannot opt out of the early `tag-preflight` step — the
    // materialized `ReleaseHookIO` would always carry the default `false`, leaving the
    // lifecycle gate to evaluate the stale pre-hook tag name.
    ReleaseTestSupport.dummyContextResource(fixturePrefix).use { _ =>
      val flagged   = ReleaseResourceHookIO
        .sideEffect[String]("flagged-before-tag")((_, _) => IO.unit)
        .copy(mayChangeTagSettings = true)
      val unflagged =
        ReleaseResourceHookIO.sideEffect[String]("plain-after-clean")((_, _) => IO.unit)
      val config    = ReleaseResourceHooks.materialize(
        ReleaseResourceHooks[String](
          beforeTagHooks = Seq(flagged),
          afterCleanCheckHooks = Seq(unflagged)
        ),
        None
      )

      IO {
        assertEquals(config.beforeTagHooks.head.mayChangeTagSettings, true)
        assertEquals(config.afterCleanCheckHooks.head.mayChangeTagSettings, false)
      }
    }
  }

  test("ReleaseResourceHooks.materialize - preserves validate function from resource hook") {
    ReleaseTestSupport.dummyContextResource(fixturePrefix).use { ctx =>
      Ref.of[IO, List[String]](Nil).flatMap { log =>
        val resourceHook = ReleaseResourceHookIO[String](
          name = "hook-with-validate",
          execute = (_: String) => (currentCtx: ReleaseContext) => IO.pure(currentCtx),
          validate = (_: ReleaseContext) => log.update(_ :+ "validated")
        )
        val hooks        = ReleaseResourceHooks[String](beforeTagHooks = Seq(resourceHook))
        val config       = ReleaseResourceHooks.materialize(hooks, Some("res"))

        config.beforeTagHooks.head.validate(ctx).flatMap { _ =>
          log.get.map(events => assertEquals(events, List("validated")))
        }
      }
    }
  }

  test("ReleaseResourceHooks.materialize - preserve tracked execute when binding the resource") {
    ReleaseTestSupport.dummyContextResource(fixturePrefix).use { ctx =>
      val metadataKey = AttributeKey[String]("materialized-tracked-resource-hook")
      val hook        = ReleaseResourceHookIO.ioTracked[String]("tracked-before-tag") {
        resource => handle =>
          handle.update(currentCtx => IO.pure(currentCtx.withMetadata(metadataKey, resource))).void
      }
      val config      =
        ReleaseResourceHooks.materialize(
          ReleaseResourceHooks[String](beforeTagHooks = Seq(hook)),
          Some("bound-resource")
        )

      TrackedContextHandle.create(ctx).flatMap { handle =>
        ReleaseHookIO.trackedExecute(config.beforeTagHooks.head)(handle) *> handle.get.map {
          result =>
            assertEquals(result.metadata(metadataKey), Some("bound-resource"))
        }
      }
    }
  }

  test("ReleaseResourceHooks.materialize - empty hooks produce empty config") {
    ReleaseTestSupport.dummyContextResource(fixturePrefix).use { _ =>
      IO {
        val config =
          ReleaseResourceHooks.materialize(
            ReleaseResourceHooks.empty[String],
            None
          )
        assertEquals(config, CoreHookConfiguration.empty)
      }
    }
  }
}
