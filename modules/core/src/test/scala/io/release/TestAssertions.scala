package io.release

import cats.effect.IO
import munit.Assertions.*

import scala.reflect.ClassTag

object TestAssertions {

  def assertFailure[E <: Throwable: ClassTag, A](io: IO[A])(check: E => Unit): IO[Unit] = {
    val expected = implicitly[ClassTag[E]].runtimeClass

    io.attempt.map {
      case Left(err) if expected.isInstance(err) =>
        check(err.asInstanceOf[E])
      case Left(other)                           =>
        fail(s"Expected ${expected.getSimpleName} but got $other")
      case Right(value)                          =>
        fail(s"Expected ${expected.getSimpleName} but got successful result: $value")
    }
  }

  def assertIllegalStateMessage[A](io: IO[A], message: String): IO[Unit] =
    assertFailure[IllegalStateException, A](io)(err => assertEquals(err.getMessage, message))
}
