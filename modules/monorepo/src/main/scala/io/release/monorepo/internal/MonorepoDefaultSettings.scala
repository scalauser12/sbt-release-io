package io.release.monorepo.internal

import cats.effect.IO
import io.release.ReleaseSharedKeys.releaseIOVersioningFile
import io.release.monorepo.*
import io.release.runtime.workflow.DefaultVersionFileIO
import sbt.*
import sbt.Keys.*

private[monorepo] object MonorepoDefaultSettings {

  // Project-scoped defaults: only keys whose definition references project-scoped
  // values (e.g. `loadedBuild.value`, `thisProjectRef.value`). Constants and
  // policy/hook/behavior toggles live in `buildDefaultSettings` at `ThisBuild`
  // scope so user `ThisBuild / ...` overrides aren't shadowed (project scope
  // wins over ThisBuild on the project axis).
  lazy val pluginDefaultSettings: Seq[Setting[?]] =
    selectionProjectDefaults

  lazy val buildDefaultSettings: Seq[Setting[?]] =
    Seq(
      behaviorDefaults,
      MonorepoLifecycle.configDefaultSettings,
      detectionDefaults,
      selectionBuildDefaults,
      versioningAndVcsDefaults,
      publishDefaults
    ).flatten

  private lazy val behaviorDefaults: Seq[Setting[?]] = Seq(
    ThisBuild / MonorepoReleasePlugin.autoImport.releaseIOMonorepoBehaviorCrossBuild  := false,
    ThisBuild / MonorepoReleasePlugin.autoImport.releaseIOMonorepoBehaviorSkipTests   := false,
    ThisBuild / MonorepoReleasePlugin.autoImport.releaseIOMonorepoBehaviorSkipPublish := false,
    ThisBuild / MonorepoReleasePlugin.autoImport.releaseIOMonorepoBehaviorInteractive := false
  )

  private lazy val detectionDefaults: Seq[Setting[?]] = Seq(
    ThisBuild / MonorepoReleasePlugin.autoImport.releaseIOMonorepoDetectionEnabled           := true,
    ThisBuild / MonorepoReleasePlugin.autoImport.releaseIOMonorepoDetectionIncludeDownstream := false,
    ThisBuild / MonorepoReleasePlugin.autoImport.releaseIOMonorepoDetectionChangeDetector    := None,
    ThisBuild / MonorepoReleasePlugin.autoImport.releaseIOMonorepoDetectionExcludes          := Seq.empty,
    ThisBuild / MonorepoReleasePlugin.autoImport.releaseIOMonorepoDetectionSharedPaths       := Seq(
      "build.sbt",
      "project/"
    )
  )

  // The ThisBuild sentinel pairs with the project-scoped default below.
  //
  // A user `ThisBuild / releaseIOMonorepoSelectionProjects := Seq(...)` (or
  // `+= ref` / `++= refs`) populates the ThisBuild value, which the project
  // default forwards. The sentinel exists so append-style overrides have a
  // base value to extend; without it, sbt rejects `ThisBuild / k += ...` at
  // build-load time as a reference to an undefined setting.
  //
  // Trade-off: the value-sentinel can't distinguish a user's explicit
  // `ThisBuild / k := Seq.empty` from the default (both surface as
  // `Seq.empty`), so an explicit empty *at ThisBuild scope* falls back to
  // the aggregate computation. To express an explicit empty selection, set
  // the key at project scope (`releaseIOMonorepoSelectionProjects :=
  // Seq.empty` on the root) — that overrides the project-scoped default
  // directly without going through the ThisBuild handshake.
  private lazy val selectionBuildDefaults: Seq[Setting[?]] = Seq(
    ThisBuild / MonorepoReleasePlugin.autoImport.releaseIOMonorepoSelectionProjects := Seq.empty
  )

  private lazy val selectionProjectDefaults: Seq[Setting[?]] = Seq(
    MonorepoReleasePlugin.autoImport.releaseIOMonorepoSelectionProjects := {
      val thisBuildValue =
        (ThisBuild / MonorepoReleasePlugin.autoImport.releaseIOMonorepoSelectionProjects).value
      if (thisBuildValue.nonEmpty) thisBuildValue
      else {
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
    }
  )

  private lazy val versioningAndVcsDefaults: Seq[Setting[?]] = Seq(
    ThisBuild / MonorepoReleasePlugin.autoImport.releaseIOMonorepoVcsReleaseCommitMessage := (
      (summary: String) => s"Setting release versions: $summary"
    ),
    ThisBuild / MonorepoReleasePlugin.autoImport.releaseIOMonorepoVcsNextCommitMessage    := (
      (summary: String) => s"Setting next versions: $summary"
    ),
    ThisBuild / MonorepoReleasePlugin.autoImport.releaseIOMonorepoVcsTagName              := (
      (name: String, ver: String) => s"$name/v$ver"
    ),
    ThisBuild / MonorepoReleasePlugin.autoImport.releaseIOMonorepoVcsTagComment           := (
      (name: String, ver: String) => s"Release $name $ver"
    ),
    ThisBuild / MonorepoReleasePlugin.autoImport.releaseIOMonorepoVersioningReadVersion   := DefaultVersionFileIO.defaultReadVersion,
    ThisBuild / MonorepoReleasePlugin.autoImport.releaseIOMonorepoVersioningFileContents  := {
      (_, ver) =>
        IO.pure(s"""version := "$ver"\n""")
    },
    ThisBuild / MonorepoReleasePlugin.autoImport.releaseIOMonorepoVersioningFile          := {
      (ref: ProjectRef, state: State) =>
        val extracted = Project.extract(state)
        extracted.get(ref / releaseIOVersioningFile)
    }
  )

  private lazy val publishDefaults: Seq[Setting[?]] = Seq(
    ThisBuild / MonorepoReleasePlugin.autoImport.releaseIOMonorepoPublishChecks := true
  )
}
