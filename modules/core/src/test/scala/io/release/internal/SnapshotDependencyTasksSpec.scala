package io.release.runtime.sbt

import cats.effect.IO
import io.release.SnapshotDependencyTasksTestCompat
import io.release.ReleaseSharedPlugin
import io.release.TestAssertions.assertFailure
import io.release.TestSupport
import munit.CatsEffectSuite
import sbt.*

import java.io.File

class SnapshotDependencyTasksSpec extends CatsEffectSuite {
  private val fixturePrefix                         = "snapshot-deps-spec"
  private val snapshotDependenciesKey               =
    ReleaseSharedPlugin.autoImport.releaseIODiagnosticsSnapshotDependencies
  private val failureCommandMessage                 =
    "Error checking for snapshot dependencies: sbt task " +
      s"'${snapshotDependenciesKey.key.label}' reported failure via FailureCommand"
  private val managedClasspathFailureCommandMessage =
    "Error checking for snapshot dependencies: sbt task " +
      s"'${sbt.Keys.managedClasspath.key.label}' reported failure via FailureCommand"

  private def failureCommandSnapshotDependenciesSetting(
      marker: File,
      dependencies: Seq[ModuleID] = Seq.empty[ModuleID]
  ): Setting[?] =
    ReleaseSharedPlugin.autoImport.releaseIODiagnosticsSnapshotDependencies := Def
      .task {
        sbt.IO.write(marker, "ran")
        dependencies
      }
      .updateState { (state: State, _: Seq[ModuleID]) =>
        state.copy(
          remainingCommands = SbtCompat.FailureCommand :: state.remainingCommands
        )
      }
      .value

  test("aggregatedSnapshotDependencies - return Right(empty) when no snapshots") {
    TestSupport.tempDirResource(s"$fixturePrefix-empty").use { dir =>
      IO.blocking {
        TestSupport.loadedState(
          dir,
          Seq(
            Project("root", dir).settings(
              ReleaseSharedPlugin.autoImport.releaseIODiagnosticsSnapshotDependencies := Seq
                .empty[ModuleID]
            )
          ),
          currentProjectId = Some("root")
        )
      }.flatMap { state =>
        SnapshotDependencyTasks.aggregatedSnapshotDependencies(state, snapshotDependenciesKey).map {
          result =>
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
                ReleaseSharedPlugin.autoImport.releaseIODiagnosticsSnapshotDependencies := Seq(depA)
              ),
            Project("child", childBase)
              .settings(
                ReleaseSharedPlugin.autoImport.releaseIODiagnosticsSnapshotDependencies := Seq(
                  depA,
                  depB
                )
              )
          ),
          currentProjectId = Some("root")
        )
      }.flatMap { state =>
        SnapshotDependencyTasks.aggregatedSnapshotDependencies(state, snapshotDependenciesKey).map {
          result =>
            assertEquals(result, Right(Seq(depA, depB)))
        }
      }
    }
  }

  test("aggregatedSnapshotDependencies - return Left when task reports FailureCommand") {
    TestSupport.tempDirResource(s"$fixturePrefix-failure-command-agg").use { dir =>
      val marker = new File(dir, "snapshot-deps-ran.txt")

      IO.blocking {
        TestSupport.loadedState(
          dir,
          Seq(Project("root", dir).settings(failureCommandSnapshotDependenciesSetting(marker))),
          currentProjectId = Some("root")
        )
      }.flatMap { state =>
        SnapshotDependencyTasks.aggregatedSnapshotDependencies(state, snapshotDependenciesKey).map {
          result =>
            assert(marker.exists())
            assertEquals(result, Left(failureCommandMessage))
        }
      }
    }
  }

  test(
    "projectSnapshotDependencies - wrap evaluation failure in IllegalStateException"
  ) {
    TestSupport.tempDirResource(s"$fixturePrefix-throw").use { dir =>
      val throwingSetting: Setting[?] =
        ReleaseSharedPlugin.autoImport.releaseIODiagnosticsSnapshotDependencies := {
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
          SnapshotDependencyTasks.projectSnapshotDependencies(
            state,
            ref,
            "test-project",
            snapshotDependenciesKey
          )
        )(err => assert(err.getMessage.contains("test-project")))
      }
    }
  }

  test("projectSnapshotDependencies - wrap FailureCommand in IllegalStateException") {
    TestSupport.tempDirResource(s"$fixturePrefix-failure-command-project").use { dir =>
      val marker = new File(dir, "snapshot-deps-ran.txt")
      val dep    = "org.example" % "dep" % "1.0.0-SNAPSHOT"

      IO.blocking {
        val state = TestSupport.loadedState(
          dir,
          Seq(
            Project("root", dir).settings(
              failureCommandSnapshotDependenciesSetting(marker, Seq(dep))
            )
          ),
          currentProjectId = Some("root")
        )
        val ref   = ProjectRef(dir.toURI, "root")
        (state, ref)
      }.flatMap { case (state, ref) =>
        assertFailure[IllegalStateException, Seq[ModuleID]](
          SnapshotDependencyTasks.projectSnapshotDependencies(
            state,
            ref,
            "test-project",
            snapshotDependenciesKey
          )
        ) { err =>
          assert(err.getMessage.contains("test-project"))
          assert(err.getCause != null)
        }
      }
    }
  }

  test(
    "projectManagedClasspathSnapshotDependencies - evaluate managedClasspath when it is defined"
  ) {
    TestSupport.tempDirResource(s"$fixturePrefix-managed-classpath-defined").use { dir =>
      val marker = new File(dir, "managed-classpath-ran.txt")
      val dep    = "org.example" % "managed-dep" % "1.0.0-SNAPSHOT"

      IO.blocking {
        val state = TestSupport.loadedState(
          dir,
          Seq(
            Project("root", dir)
              .enablePlugins(sbt.plugins.JvmPlugin)
              .settings(SnapshotDependencyTasksTestCompat.managedClasspathSetting(marker, Seq(dep)))
          ),
          currentProjectId = Some("root")
        )
        val ref   =
          Project
            .extract(state)
            .structure
            .allProjectRefs
            .find(_.project == "root")
            .getOrElse(sys.error("missing root project ref"))
        (state, ref)
      }.flatMap { case (state, ref) =>
        SnapshotDependencyTasks.projectManagedClasspathSnapshotDependencies(state, ref).map {
          result =>
            assert(marker.exists())
            assertEquals(result, Seq(dep))
        }
      }
    }
  }

  test(
    "projectManagedClasspathSnapshotDependencies - wrap FailureCommand in IllegalStateException"
  ) {
    TestSupport.tempDirResource(s"$fixturePrefix-failure-command-managed-classpath").use { dir =>
      IO.blocking {
        val baseState = TestSupport.loadedState(
          dir,
          Seq(Project("root", dir)),
          currentProjectId = Some("root")
        )
        val state     = baseState.copy(
          remainingCommands = SbtCompat.FailureCommand :: baseState.remainingCommands
        )
        val ref       = ProjectRef(dir.toURI, "root")
        (state, ref)
      }.flatMap { case (state, ref) =>
        assert(SbtRuntime.hasFailureCommand(state))
        assertFailure[IllegalStateException, Seq[ModuleID]](
          SnapshotDependencyTasks.projectManagedClasspathSnapshotDependencies(state, ref)
        ) { err =>
          assertEquals(err.getMessage, managedClasspathFailureCommandMessage)
        }
      }
    }
  }

  test(
    "projectManagedClasspathSnapshotDependencies - return empty when managedClasspath is undefined"
  ) {
    TestSupport.tempDirResource(s"$fixturePrefix-managed-classpath-undefined").use { dir =>
      IO.blocking {
        val state = TestSupport.loadedState(
          dir,
          Seq(Project("root", dir)),
          currentProjectId = Some("root")
        )
        val ref   = ProjectRef(dir.toURI, "root")
        (state, ref)
      }.flatMap { case (state, ref) =>
        SnapshotDependencyTasks.projectManagedClasspathSnapshotDependencies(state, ref).map {
          result =>
            assertEquals(result, Seq.empty[ModuleID])
        }
      }
    }
  }
}
