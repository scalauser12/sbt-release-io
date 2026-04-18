package io.release

import cats.effect.Deferred
import cats.effect.IO
import io.release.runtime.TrackedContextHandle
import munit.CatsEffectSuite
import scala.concurrent.duration.DurationInt

class TrackedContextHandleSpec extends CatsEffectSuite {

  test("TrackedContextHandle.update - serialize concurrent checkpoint updates") {
    for {
      started     <- Deferred[IO, Unit]
      allowFirst  <- Deferred[IO, Unit]
      handle      <- TrackedContextHandle.create(Vector.empty[String])
      firstFiber  <-
        handle
          .update(current => started.complete(()) *> allowFirst.get.as(current :+ "first"))
          .start
      _           <- started.get
      secondFiber <- handle
                       .update(current => IO.pure(current :+ "second"))
                       .start
      _           <- allowFirst.complete(())
      _           <- firstFiber.joinWithNever
      _           <- secondFiber.joinWithNever
      result      <- handle.get
    } yield assertEquals(result, Vector("first", "second"))
  }

  test("TrackedContextHandle.restoreLatest - serialize restore with concurrent updates") {
    for {
      started      <- Deferred[IO, Unit]
      allowRestore <- Deferred[IO, Unit]
      handle       <- TrackedContextHandle.create(Vector("seed"))
      restoreFiber <- TrackedContextHandle
                        .restoreLatest(handle)(
                          restore = current =>
                            started.complete(()) *> allowRestore.get.as(current :+ "restored"),
                          onRestoreError = (_, err) => IO.raiseError(err)
                        )
                        .start
      _            <- started.get
      updateFiber  <- handle
                        .update(current => IO.pure(current :+ "concurrent"))
                        .start
      _            <- allowRestore.complete(())
      _            <- restoreFiber.joinWithNever
      _            <- updateFiber.joinWithNever
      result       <- handle.get
    } yield assertEquals(result, Vector("seed", "restored", "concurrent"))
  }

  test("TrackedContextHandle.update - nested get fails fast instead of deadlocking") {
    for {
      handle <- TrackedContextHandle.create(Vector("seed"))
      result <- handle
                  .update(current => handle.get.as(current :+ "unreachable"))
                  .attempt
                  .timeout(3.seconds)
      latest <- handle.get.timeout(3.seconds)
    } yield {
      result match {
        case Left(err: IllegalStateException) =>
          assert(err.getMessage.contains("Nested tracked-handle operations"))
        case other                            =>
          fail(s"Expected nested handle access to fail fast, got $other")
      }
      assertEquals(latest, Vector("seed"))
    }
  }

  test("TrackedContextHandle.update - nested update fails fast instead of deadlocking") {
    for {
      handle <- TrackedContextHandle.create(Vector("seed"))
      result <- handle
                  .update(current =>
                    handle.update(inner => IO.pure(inner :+ "nested")).as(current :+ "outer")
                  )
                  .attempt
                  .timeout(3.seconds)
      latest <- handle.get.timeout(3.seconds)
    } yield {
      result match {
        case Left(err: IllegalStateException) =>
          assert(err.getMessage.contains("Nested tracked-handle operations"))
        case other                            =>
          fail(s"Expected nested handle access to fail fast, got $other")
      }
      assertEquals(latest, Vector("seed"))
    }
  }

  test("TrackedContextHandle.update - child fiber get succeeds after parent callback returns") {
    for {
      childCanRun <- Deferred[IO, Unit]
      childResult <- Deferred[IO, Either[Throwable, Vector[String]]]
      handle      <- TrackedContextHandle.create(Vector("seed"))
      _           <- handle.update { current =>
                       (childCanRun.get *> handle.get).attempt
                         .flatMap(childResult.complete)
                         .start
                         .as(current :+ "parent")
                     }
      _           <- childCanRun.complete(())
      child       <- childResult.get.timeout(3.seconds)
      latest      <- handle.get.timeout(3.seconds)
    } yield {
      child match {
        case Right(result) => assertEquals(result, Vector("seed", "parent"))
        case Left(err)     => fail(s"Expected child fiber get to succeed, got $err")
      }
      assertEquals(latest, Vector("seed", "parent"))
    }
  }

  test("TrackedContextHandle.update - child fiber update succeeds after parent callback returns") {
    for {
      childCanRun <- Deferred[IO, Unit]
      childResult <- Deferred[IO, Either[Throwable, Vector[String]]]
      handle      <- TrackedContextHandle.create(Vector("seed"))
      _           <- handle.update { current =>
                       (childCanRun.get *> handle.update(inner => IO.pure(inner :+ "child"))).attempt
                         .flatMap(childResult.complete)
                         .start
                         .as(current :+ "parent")
                     }
      _           <- childCanRun.complete(())
      child       <- childResult.get.timeout(3.seconds)
      latest      <- handle.get.timeout(3.seconds)
    } yield {
      child match {
        case Right(result) => assertEquals(result, Vector("seed", "parent", "child"))
        case Left(err)     => fail(s"Expected child fiber update to succeed, got $err")
      }
      assertEquals(latest, Vector("seed", "parent", "child"))
    }
  }
}
