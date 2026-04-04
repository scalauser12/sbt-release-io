package io.release.monorepo

import cats.effect.IO
import io.release.ReleaseIO.releaseIOVersioningFile
import io.release.steps.VersionSteps
import sbt.*
import sbt.Keys.*

private[monorepo] object MonorepoDefaultSettings {

  lazy val pluginDefaultSettings: Seq[Setting[?]] =
    Seq(
      behaviorDefaults,
      MonorepoLifecycle.configDefaultSettings,
      selectionAndDetectionDefaults,
      versioningAndVcsDefaults,
      publishDefaults
    ).flatten

  private lazy val behaviorDefaults: Seq[Setting[?]] = Seq(
    MonorepoReleaseIO.releaseIOMonorepoBehaviorCrossBuild  := false,
    MonorepoReleaseIO.releaseIOMonorepoBehaviorSkipTests   := false,
    MonorepoReleaseIO.releaseIOMonorepoBehaviorSkipPublish := false,
    MonorepoReleaseIO.releaseIOMonorepoBehaviorInteractive := false
  )

  private lazy val selectionAndDetectionDefaults: Seq[Setting[?]] = Seq(
    MonorepoReleaseIO.releaseIOMonorepoDetectionEnabled           := true,
    MonorepoReleaseIO.releaseIOMonorepoDetectionIncludeDownstream := false,
    MonorepoReleaseIO.releaseIOMonorepoDetectionChangeDetector    := None,
    MonorepoReleaseIO.releaseIOMonorepoDetectionExcludes          := Seq.empty,
    MonorepoReleaseIO.releaseIOMonorepoDetectionSharedPaths       := Seq("build.sbt", "project/"),
    MonorepoReleaseIO.releaseIOMonorepoSelectionProjects          := {
      val build      = loadedBuild.value
      val root       = thisProjectRef.value
      val projectMap = build.allProjectRefs.map { case (ref, proj) =>
        ref -> proj.aggregate
      }.toMap

      def transitive(ref: ProjectRef, visited: Set[ProjectRef]): Seq[ProjectRef] =
        if (visited.contains(ref)) Seq.empty
        else {
          val directAggs = projectMap.getOrElse(ref, Seq.empty)
          directAggs.flatMap(agg => agg +: transitive(agg, visited + ref))
        }

      transitive(root, Set.empty).distinct
    }
  )

  private lazy val versioningAndVcsDefaults: Seq[Setting[?]] = Seq(
    MonorepoReleaseIO.releaseIOMonorepoVcsReleaseCommitMessage := ((summary: String) =>
      s"Setting release versions: $summary"
    ),
    MonorepoReleaseIO.releaseIOMonorepoVcsNextCommitMessage    := ((summary: String) =>
      s"Setting next versions: $summary"
    ),
    MonorepoReleaseIO.releaseIOMonorepoVcsTagName              := ((name: String, ver: String) =>
      s"$name/v$ver"
    ),
    MonorepoReleaseIO.releaseIOMonorepoVcsTagComment           := ((name: String, ver: String) =>
      s"Release $name $ver"
    ),
    MonorepoReleaseIO.releaseIOMonorepoVersioningReadVersion   := VersionSteps.defaultReadVersion,
    MonorepoReleaseIO.releaseIOMonorepoVersioningFileContents  := { (_, ver) =>
      IO.pure(s"""version := "$ver"\n""")
    },
    MonorepoReleaseIO.releaseIOMonorepoVersioningFile          := { (ref: ProjectRef, state: State) =>
      Project.extract(state).get(ref / releaseIOVersioningFile)
    }
  )

  private lazy val publishDefaults: Seq[Setting[?]] = Seq(
    MonorepoReleaseIO.releaseIOMonorepoPublishChecks := true
  )
}
