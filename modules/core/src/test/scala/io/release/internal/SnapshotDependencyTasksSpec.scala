package io.release.internal

import cats.effect.IO
import io.release.{ReleaseIO, TestSupport}
import munit.CatsEffectSuite
import sbt.{Project, ProjectRef, *}

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
      val state: State    = TestSupport.loadedState(
        dir,
        Seq(Project("root", dir).settings(throwingSetting)),
        currentProjectId = Some("root")
      )
      val ref: ProjectRef = ProjectRef(dir.toURI, "root")
      SnapshotDependencyTasks
        .projectSnapshotDependencies(state, ref, "test-project")
        .attempt
        .map { result =>
          assert(result.isLeft)
          val err = result.left.toOption.get
          assert(err.isInstanceOf[IllegalStateException])
          assert(err.getMessage.contains("test-project"))
        }
    }
  }
}
