package io.release.monorepo

import cats.effect.IO
import io.release.ReleasePluginIO.autoImport.releaseIOVersioningFile
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
    MonorepoReleasePlugin.autoImport.releaseIOMonorepoBehaviorCrossBuild  := false,
    MonorepoReleasePlugin.autoImport.releaseIOMonorepoBehaviorSkipTests   := false,
    MonorepoReleasePlugin.autoImport.releaseIOMonorepoBehaviorSkipPublish := false,
    MonorepoReleasePlugin.autoImport.releaseIOMonorepoBehaviorInteractive := false
  )

  private lazy val selectionAndDetectionDefaults: Seq[Setting[?]] = Seq(
    MonorepoReleasePlugin.autoImport.releaseIOMonorepoDetectionEnabled           := true,
    MonorepoReleasePlugin.autoImport.releaseIOMonorepoDetectionIncludeDownstream := false,
    MonorepoReleasePlugin.autoImport.releaseIOMonorepoDetectionChangeDetector    := None,
    MonorepoReleasePlugin.autoImport.releaseIOMonorepoDetectionExcludes          := Seq.empty,
    MonorepoReleasePlugin.autoImport.releaseIOMonorepoDetectionSharedPaths       := Seq(
      "build.sbt",
      "project/"
    ),
    MonorepoReleasePlugin.autoImport.releaseIOMonorepoSelectionProjects          := {
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
    MonorepoReleasePlugin.autoImport.releaseIOMonorepoVcsReleaseCommitMessage := (
      (summary: String) => s"Setting release versions: $summary"
    ),
    MonorepoReleasePlugin.autoImport.releaseIOMonorepoVcsNextCommitMessage    := ((summary: String) =>
      s"Setting next versions: $summary"
    ),
    MonorepoReleasePlugin.autoImport.releaseIOMonorepoVcsTagName              := ((name: String, ver: String) =>
      s"$name/v$ver"
    ),
    MonorepoReleasePlugin.autoImport.releaseIOMonorepoVcsTagComment           := (
      (name: String, ver: String) => s"Release $name $ver"
    ),
    MonorepoReleasePlugin.autoImport.releaseIOMonorepoVersioningReadVersion   := VersionSteps.defaultReadVersion,
    MonorepoReleasePlugin.autoImport.releaseIOMonorepoVersioningFileContents  := { (_, ver) =>
      IO.pure(s"""version := "$ver"\n""")
    },
    MonorepoReleasePlugin.autoImport.releaseIOMonorepoVersioningFile          := {
      (ref: ProjectRef, state: State) =>
        Project.extract(state).get(ref / releaseIOVersioningFile)
    }
  )

  private lazy val publishDefaults: Seq[Setting[?]] = Seq(
    MonorepoReleasePlugin.autoImport.releaseIOMonorepoPublishChecks := true
  )
}
