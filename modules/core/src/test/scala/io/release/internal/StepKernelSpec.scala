package io.release.internal

import cats.effect.IO
import cats.effect.Ref
import io.release.TestRepoFiles
import munit.CatsEffectSuite

class StepKernelSpec extends CatsEffectSuite {

  test("normalizedValidationPair composes plain and threaded validation in order") {
    Ref.of[IO, List[String]](Nil).flatMap { events =>
      val validate = (_: Int) => events.update(_ :+ "validate")
      val threaded = (value: Int) => events.update(_ :+ "threaded").as(value + 1)

      val (publicValidate, normalized) =
        StepKernel.normalizedValidationPair(validate, Some(threaded))

      for {
        _         <- publicValidate(1)
        observed1 <- events.get
        _         <- events.set(Nil)
        result    <- normalized.get.apply(1)
        observed2 <- events.get
      } yield {
        assertEquals(observed1, List("validate", "threaded"))
        assertEquals(result, 2)
        assertEquals(observed2, List("validate", "threaded"))
      }
    }
  }

  test("normalizedValidationPair composes per-item plain and threaded validation in order") {
    Ref.of[IO, List[String]](Nil).flatMap { events =>
      val validate: (Int, String) => IO[Unit] =
        (_: Int, item: String) => events.update(_ :+ s"validate:$item")
      val threaded                            =
        (value: Int, item: String) => events.update(_ :+ s"threaded:$item").as(value + 1)

      val (publicValidate, normalized) =
        StepKernel.normalizedValidationPair[Int, String](validate, Some(threaded))

      for {
        _         <- publicValidate(1, "core")
        observed1 <- events.get
        _         <- events.set(Nil)
        result    <- normalized.get.apply(1, "core")
        observed2 <- events.get
      } yield {
        assertEquals(observed1, List("validate:core", "threaded:core"))
        assertEquals(result, 2)
        assertEquals(observed2, List("validate:core", "threaded:core"))
      }
    }
  }

  test("single builder and single resource builder compose validations with the same ordering") {
    Ref.of[IO, List[String]](Nil).flatMap { events =>
      val plainStep = ProcessStep
        .single[Int]("plain")
        .withValidationContext(value => events.update(_ :+ s"plain:first:$value").as(value + 1))
        .withValidationContext(value => events.update(_ :+ s"plain:second:$value").as(value + 1))
        .validateOnly

      val resourceStep =
        ProcessStep
          .singleResource[String, Int]("resource")
          .withValidationContext(resource =>
            value => events.update(_ :+ s"resource:first:$resource:$value").as(value + 1)
          )
          .withValidationContext(resource =>
            value => events.update(_ :+ s"resource:second:$resource:$value").as(value + 1)
          )
          .validateOnly("demo")

      for {
        plainResult    <- plainStep.threadedValidation(1)
        plainObserved  <- events.get
        _              <- events.set(Nil)
        resourceResult <- resourceStep.threadedValidation(1)
        resourceSeen   <- events.get
      } yield {
        assertEquals(plainResult, 3)
        assertEquals(plainObserved, List("plain:first:1", "plain:second:2"))
        assertEquals(resourceResult, 3)
        assertEquals(resourceSeen, List("resource:first:demo:1", "resource:second:demo:2"))
      }
    }
  }

  test("item builder and item resource builder compose validations with the same ordering") {
    Ref.of[IO, List[String]](Nil).flatMap { events =>
      val plainStep = ProcessStep
        .perItem[Int, String]("plain-item")
        .withValidationContext((value, item) =>
          events.update(_ :+ s"plain:first:$item:$value").as(value + 1)
        )
        .withValidationContext((value, item) =>
          events.update(_ :+ s"plain:second:$item:$value").as(value + 1)
        )
        .validateOnly

      val resourceStep =
        ProcessStep
          .perItemResource[String, Int, String]("resource-item")
          .withValidationContext(resource =>
            (value, item) =>
              events.update(_ :+ s"resource:first:$resource:$item:$value").as(value + 1)
          )
          .withValidationContext(resource =>
            (value, item) =>
              events.update(_ :+ s"resource:second:$resource:$item:$value").as(value + 1)
          )
          .validateOnly("demo")

      for {
        plainResult    <- plainStep.threadedValidation(1, "api")
        plainObserved  <- events.get
        _              <- events.set(Nil)
        resourceResult <- resourceStep.threadedValidation(1, "api")
        resourceSeen   <- events.get
      } yield {
        assertEquals(plainResult, 3)
        assertEquals(plainObserved, List("plain:first:api:1", "plain:second:api:2"))
        assertEquals(resourceResult, 3)
        assertEquals(resourceSeen, List("resource:first:demo:api:1", "resource:second:demo:api:2"))
      }
    }
  }

  test("source cleanup - wrapper-era copy helpers are gone") {
    IO {
      val source =
        TestRepoFiles.readString("modules/core/src/main/scala/io/release/internal/StepKernel.scala")

      assert(!source.contains("SingleBuilderState"))
      assert(!source.contains("ItemBuilderState"))
      assert(!source.contains("SingleResourceBuilderState"))
      assert(!source.contains("ItemResourceBuilderState"))
    }
  }
}
