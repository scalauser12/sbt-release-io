package io.release.internal

import cats.effect.IO
import io.release.ReleasePluginIO
import io.release.TestAssertions.assertFailure
import io.release.TestSupport
import munit.CatsEffectSuite
import sbt.*

import java.io.File

class SnapshotDependencyTasksSpec extends CatsEffectSuite {
  private val fixturePrefix = "snapshot-deps-spec"

  test("aggregatedSnapshotDependencies - return Right(empty) when no snapshots") {
    TestSupport.tempDirResource(s"$fixturePrefix-empty").use { dir =>
      IO.blocking {
        TestSupport.loadedState(
          dir,
          Seq(
            Project("root", dir).settings(
              ReleasePluginIO.autoImport.releaseIODiagnosticsSnapshotDependencies := Seq
                .empty[ModuleID]
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

  test("aggregatedSnapshotDependencies - deduplicate repeated dependencies in first-seen order") {
    TestSupport.tempDirResource(s"$fixturePrefix-distinct").use { dir =>
      val depA = "org.example" % "demo"  % "1.0.0-SNAPSHOT"
      val depB = "org.example" % "other" % "2.0.0-SNAPSHOT"

      IO.blocking {
        val childBase = new File(dir, "child")
        childBase.mkdirs()

        TestSupport.loadedState(
          dir,
          Seq(
            Project("root", dir)
              .aggregate(LocalProject("child"))
              .settings(
                ReleasePluginIO.autoImport.releaseIODiagnosticsSnapshotDependencies := Seq(depA)
              ),
            Project("child", childBase)
              .settings(
                ReleasePluginIO.autoImport.releaseIODiagnosticsSnapshotDependencies := Seq(
                  depA,
                  depB
                )
              )
          ),
          currentProjectId = Some("root")
        )
      }.flatMap { state =>
        SnapshotDependencyTasks.aggregatedSnapshotDependencies(state).map { result =>
          assertEquals(result, Right(Seq(depA, depB)))
        }
      }
    }
  }

  test(
    "projectSnapshotDependencies - wrap evaluation failure in IllegalStateException"
  ) {
    TestSupport.tempDirResource(s"$fixturePrefix-throw").use { dir =>
      val throwingSetting: Setting[?] =
        ReleasePluginIO.autoImport.releaseIODiagnosticsSnapshotDependencies := {
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
