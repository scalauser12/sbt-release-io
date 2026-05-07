package io.release.runtime.sbt

import cats.effect.IO
import io.release.ReleasePluginIO.autoImport.releaseIOPublishAction
import io.release.TestSupport
import munit.CatsEffectSuite
import sbt.*
import sbt.Keys

import java.io.File

/** Coverage for [[AggregatePublishTargets]], which depends on the unstable
  * `sbt.internal.Aggregation.aggregationEnabled` API. The
  * `publish-multi-project-manifest` scripted fixture only covers the all-
  * aggregated happy path; this spec locks the `aggregate := false` pruning
  * branch so an sbt-internal API drift fails fast in `sbt test` rather than
  * waiting for the slower scripted run. Lives under `modules/core` because
  * `modules/runtime` has no test root today.
  */
class AggregatePublishTargetsSpec extends CatsEffectSuite {
  private val fixturePrefix = "aggregate-publish-targets-spec"

  test(
    "of - includes the root and every aggregated child when no aggregation is disabled"
  ) {
    twoProjectFixture(s"$fixturePrefix-happy", rootSettings = Seq.empty).map { state =>
      val targets = AggregatePublishTargets.fromState(state, releaseIOPublishAction)
      assertEquals(targets.map(_.project).toSet, Set("root", "child"))
    }
  }

  test(
    "of - prunes children when the root sets `releaseIOPublishAction / aggregate := false`"
  ) {
    twoProjectFixture(
      s"$fixturePrefix-root-prune",
      rootSettings = Seq(releaseIOPublishAction / Keys.aggregate := false)
    ).map { state =>
      val targets = AggregatePublishTargets.fromState(state, releaseIOPublishAction)
      assertEquals(
        targets.map(_.project).toList,
        List("root"),
        "root with aggregate := false on the publish key returns only itself"
      )
    }
  }

  test(
    "of - includes a non-aggregating intermediate project but skips its descendants"
  ) {
    threeProjectFixture(
      s"$fixturePrefix-mid-prune",
      midSettings = Seq(releaseIOPublishAction / Keys.aggregate := false)
    ).map { state =>
      val targets = AggregatePublishTargets.fromState(state, releaseIOPublishAction)
      // `root` aggregates `mid` (which disables aggregation) and `leaf` is
      // only reached transitively through `mid`. The walk must include
      // `root` and `mid` (mid still publishes — `aggregate := false` only
      // means mid does not propagate publish to ITS children) but must NOT
      // descend into `leaf`.
      assertEquals(
        targets.map(_.project).toSet,
        Set("root", "mid"),
        s"expected {root, mid} but got ${targets.map(_.project)}"
      )
    }
  }

  // Note on coverage of the defensive `Seq.empty` branch in
  // `AggregatePublishTargets.resolve` (the `units.get(ref.build).flatMap(_.defined.get(ref.project))
  // .getOrElse(Seq.empty)` path): that branch is a "trust no one" safety net
  // for a state sbt's loader prevents from existing — `TestBuildState`
  // validates every aggregate ref and rejects unknown LocalProject IDs / non-
  // local ProjectRefs at fixture-build time, so we cannot construct it
  // through the public test harness. Reaching it would require reflecting
  // into `Extracted.structure.units` to mutate a project's `aggregate` list
  // post-hoc, which would lock the test to sbt internals more tightly than
  // the production code itself. Left uncovered intentionally; the branch is
  // small and only fires on a malformed structure that sbt cannot produce.

  // ── helpers ─────────────────────────────────────────────────────────

  private def twoProjectFixture(
      prefix: String,
      rootSettings: Seq[Setting[?]]
  ): IO[State] =
    TestSupport.tempDirResource(prefix).use { dir =>
      IO.blocking {
        val childBase = new File(dir, "child")
        childBase.mkdirs()
        TestSupport.loadedState(
          dir,
          Seq(
            Project("root", dir)
              .aggregate(LocalProject("child"))
              .settings(rootSettings*),
            Project("child", childBase)
          ),
          currentProjectId = Some("root")
        )
      }
    }

  private def threeProjectFixture(
      prefix: String,
      midSettings: Seq[Setting[?]]
  ): IO[State] =
    TestSupport.tempDirResource(prefix).use { dir =>
      IO.blocking {
        val midBase  = new File(dir, "mid")
        val leafBase = new File(dir, "leaf")
        midBase.mkdirs()
        leafBase.mkdirs()
        TestSupport.loadedState(
          dir,
          Seq(
            Project("root", dir).aggregate(LocalProject("mid")),
            Project("mid", midBase)
              .aggregate(LocalProject("leaf"))
              .settings(midSettings*),
            Project("leaf", leafBase)
          ),
          currentProjectId = Some("root")
        )
      }
    }
}
