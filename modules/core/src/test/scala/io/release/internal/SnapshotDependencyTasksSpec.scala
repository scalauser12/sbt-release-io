package io.release.internal

import cats.effect.IO
import io.release.ReleaseIO
import io.release.TestAssertions.assertFailure
import io.release.TestSupport
import munit.CatsEffectSuite
import sbt.*

class SnapshotDependencyTasksSpec extends CatsEffectSuite {
  private val fixturePrefix = "snapshot-deps-spec"

  test("aggregatedSnapshotDependencies - return Right(empty) when no snapshots") {
    TestSupport.tempDirResource(s"$fixturePrefix-empty").use { dir =>
      IO.blocking {
        TestSupport.loadedState(
          dir,
          Seq(
            Project("root", dir).settings(
              ReleaseIO.releaseIOSnapshotDependencies := Seq.empty[ModuleID]
            )
          ),
          currentProjectId = Some("root")
        )
      }.flatMap { state =>
        SnapshotDependencyTasks.aggregatedSnapshotDependencies(state).map { result =>
          assertEquals(result, Right(Seq.empty[ModuleID]))
        }
      }
    }
  }

  test(
    "projectSnapshotDependencies - wrap evaluation failure in IllegalStateException"
  ) {
    TestSupport.tempDirResource(s"$fixturePrefix-throw").use { dir =>
      val throwingSetting: Setting[?] =
        ReleaseIO.releaseIOSnapshotDependencies := {
          throw new RuntimeException("snapshot deps eval error")
          Seq.empty[ModuleID]
        }
      IO.blocking {
        val state = TestSupport.loadedState(
          dir,
          Seq(Project("root", dir).settings(throwingSetting)),
          currentProjectId = Some("root")
        )
        val ref   = ProjectRef(dir.toURI, "root")
        (state, ref)
      }.flatMap { case (state, ref) =>
        assertFailure[IllegalStateException, Seq[ModuleID]](
          SnapshotDependencyTasks.projectSnapshotDependencies(state, ref, "test-project")
        )(err => assert(err.getMessage.contains("test-project")))
      }
    }
  }
}
