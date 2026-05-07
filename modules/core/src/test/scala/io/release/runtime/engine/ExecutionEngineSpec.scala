package io.release.runtime.engine

import cats.effect.IO
import cats.effect.Ref
import io.release.ReleaseContext
import io.release.ReleaseTestSupport
import io.release.runtime.ReleaseLogPrefixes
import munit.CatsEffectSuite

/** Coverage for the validate→execute clearance boundary in
  * [[ExecutionEngine.runMainSegment]] and
  * [[ExecutionEngine.runSequentialValidateThenExecute]]. The hook-precondition
  * scripted fixtures cover the same contract end-to-end, but exercise the
  * full release pipeline and run too slowly to catch a regression in the
  * `cleanedCtx = if (validatedCtx.failed) ... else validatedCtx.clearTentativeSeeds`
  * line itself. This spec calls the engine directly with a fake step so an
  * accidental edit to that wiring fails in `sbt test` instead of waiting for
  * scripted. Lives under `modules/core` because `modules/runtime` has no
  * test root today (same rationale as `AggregatePublishTargetsSpec`).
  */
class ExecutionEngineSpec extends CatsEffectSuite {

  test("runMainSegment - clear tentative seeds at the validate→execute boundary") {
    ReleaseTestSupport.dummyContextResource("execution-engine-spec-clear").use { ctx =>
      Ref.of[IO, Option[String]](None).flatMap { observed =>
        val step = ExecutionEngine.PreparedStep[ReleaseContext](
          name = "fake-seeder",
          validate = (c: ReleaseContext) =>
            IO.pure(c.withVersions("1.0.0", "1.1.0-SNAPSHOT").markVersionsTentativelySeeded),
          execute = (c: ReleaseContext) => observed.set(c.releaseVersion).as(c)
        )

        ExecutionEngine
          .runMainSegment[ReleaseContext](
            ReleaseLogPrefixes.Core,
            Seq(step),
            ctx,
            armOnFailure = ExecutionEngine.armOnFailure[ReleaseContext]
          )
          .flatMap { result =>
            observed.get.map { seenInExecute =>
              assertEquals(
                seenInExecute,
                None,
                "execute must observe ctx.releaseVersion = None after the boundary clears the seed"
              )
              assertEquals(
                result.releaseVersion,
                None,
                "result ctx must not carry the tentative version into post-execute"
              )
              assertEquals(
                result.metadata(ReleaseContext.tentativelySeededVersionsKey),
                None,
                "tentative marker must be stripped after a successful clearance"
              )
              assert(!result.failed, "successful run must not be marked failed")
            }
          }
      }
    }
  }

  test(
    "runMainSegment - preserve explicit ctx.versions installed without the tentative marker"
  ) {
    ReleaseTestSupport.dummyContextResource("execution-engine-spec-explicit").use { ctx =>
      Ref.of[IO, Option[String]](None).flatMap { observed =>
        val step = ExecutionEngine.PreparedStep[ReleaseContext](
          name = "fake-explicit-seeder",
          // No `markVersionsTentativelySeeded` — simulates a CLI override or
          // hook that installed the version pair explicitly.
          validate = (c: ReleaseContext) => IO.pure(c.withVersions("1.0.0", "1.1.0-SNAPSHOT")),
          execute = (c: ReleaseContext) => observed.set(c.releaseVersion).as(c)
        )

        ExecutionEngine
          .runMainSegment[ReleaseContext](
            ReleaseLogPrefixes.Core,
            Seq(step),
            ctx,
            armOnFailure = ExecutionEngine.armOnFailure[ReleaseContext]
          )
          .flatMap { result =>
            observed.get.map { seenInExecute =>
              assertEquals(
                seenInExecute,
                Some("1.0.0"),
                "execute must observe explicitly-installed versions across the boundary"
              )
              assertEquals(result.releaseVersion, Some("1.0.0"))
              assertEquals(result.nextVersion, Some("1.1.0-SNAPSHOT"))
            }
          }
      }
    }
  }

  test("runMainSegment - skip clearance when validation fails") {
    ReleaseTestSupport.dummyContextResource("execution-engine-spec-fail").use { ctx =>
      Ref.of[IO, Option[String]](None).flatMap { observed =>
        val step = ExecutionEngine.PreparedStep[ReleaseContext](
          name = "fake-failing-seeder",
          validate = (c: ReleaseContext) =>
            IO.pure(
              c.withVersions("1.0.0", "1.1.0-SNAPSHOT")
                .markVersionsTentativelySeeded
                .failWith(new RuntimeException("validate fail"))
            ),
          execute = (c: ReleaseContext) => observed.set(Some("EXECUTED")).as(c)
        )

        ExecutionEngine
          .runMainSegment[ReleaseContext](
            ReleaseLogPrefixes.Core,
            Seq(step),
            ctx,
            armOnFailure = ExecutionEngine.armOnFailure[ReleaseContext]
          )
          .flatMap { result =>
            observed.get.map { seenInExecute =>
              assert(result.failed, "validation failure must propagate to result.failed")
              assertEquals(
                seenInExecute,
                None,
                "execute must not run when validation has already failed"
              )
              assertEquals(
                result.releaseVersion,
                Some("1.0.0"),
                "seeded snapshot must survive when clearance is skipped on failed validation"
              )
              assert(
                result.metadata(ReleaseContext.tentativelySeededVersionsKey).isDefined,
                "tentative marker must survive on failure so a future failure-context summary " +
                  "can inspect what the validate-time resolution produced"
              )
            }
          }
      }
    }
  }

  test(
    "runSequentialValidateThenExecute - clear tentative seeds at the per-step boundary"
  ) {
    ReleaseTestSupport.dummyContextResource("execution-engine-spec-sequential").use { ctx =>
      Ref.of[IO, Option[String]](None).flatMap { observed =>
        val step = ExecutionEngine.PreparedStep[ReleaseContext](
          name = "fake-seeder-sequential",
          validate = (c: ReleaseContext) =>
            IO.pure(c.withVersions("1.0.0", "1.1.0-SNAPSHOT").markVersionsTentativelySeeded),
          execute = (c: ReleaseContext) => observed.set(c.releaseVersion).as(c)
        )

        ExecutionEngine
          .runSequentialValidateThenExecute[ReleaseContext](
            Seq(step),
            ctx
          )
          .flatMap { result =>
            observed.get.map { seenInExecute =>
              assertEquals(
                seenInExecute,
                None,
                "sequential per-step boundary must also clear the tentative seed before execute"
              )
              assertEquals(result.releaseVersion, None)
              assertEquals(
                result.metadata(ReleaseContext.tentativelySeededVersionsKey),
                None
              )
            }
          }
      }
    }
  }
}
