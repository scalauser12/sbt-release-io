package io.release.monorepo.internal

import cats.effect.IO
import io.release.internal.ExecutionFlags
import io.release.monorepo.{MonorepoContext, MonorepoTagStrategy, ProjectReleaseInfo}
import sbt.*

/** How project selection is determined for a monorepo release. */
private[monorepo] sealed trait SelectionMode

private[monorepo] object SelectionMode {
  case object ExplicitSelection extends SelectionMode
  case object AllChanged        extends SelectionMode
  case object DetectChanges     extends SelectionMode
}

/** Resolved version inputs for one monorepo project. */
private[monorepo] final case class ProjectVersionPlan(
    versionFile: File,
    releaseVersionOverride: Option[String],
    nextVersionOverride: Option[String]
) {
  def initialVersions: Option[(String, String)] = {
    val release = releaseVersionOverride.getOrElse("")
    val next    = nextVersionOverride.getOrElse("")
    if (release.nonEmpty || next.nonEmpty) Some((release, next)) else None
  }
}

/** Typed project metadata used by the planner/execution layer. */
private[monorepo] final case class ProjectPlan(
    ref: ProjectRef,
    name: String,
    baseDir: File,
    version: ProjectVersionPlan
) {
  def toReleaseInfo: ProjectReleaseInfo =
    ProjectReleaseInfo(
      ref = ref,
      name = name,
      baseDir = baseDir,
      versionFile = version.versionFile,
      versions = version.initialVersions
    )
}

/** Typed project-selection plan used at the detect/select step. */
private[monorepo] sealed trait ProjectSelectionPlan {
  def mode: SelectionMode
  def resolve(ctx: MonorepoContext): IO[Seq[ProjectPlan]]
}

private[monorepo] object ProjectSelectionPlan {
  final case class Explicit(projects: Seq[ProjectPlan]) extends ProjectSelectionPlan {
    val mode: SelectionMode                                 = SelectionMode.ExplicitSelection
    def resolve(ctx: MonorepoContext): IO[Seq[ProjectPlan]] =
      IO.pure(projects)
  }

  final case class AllProjects(projects: Seq[ProjectPlan]) extends ProjectSelectionPlan {
    val mode: SelectionMode                                 = SelectionMode.AllChanged
    def resolve(ctx: MonorepoContext): IO[Seq[ProjectPlan]] =
      IO.pure(projects)
  }

  final case class Detect(run: MonorepoContext => IO[Seq[ProjectPlan]])
      extends ProjectSelectionPlan {
    val mode: SelectionMode                                 = SelectionMode.DetectChanges
    def resolve(ctx: MonorepoContext): IO[Seq[ProjectPlan]] =
      run(ctx)
  }
}

/** Typed execution plan for the monorepo release command. */
private[monorepo] final case class MonorepoReleasePlan(
    flags: ExecutionFlags,
    tagStrategy: MonorepoTagStrategy,
    allProjects: Seq[ProjectPlan],
    orderedProjects: Seq[ProjectPlan],
    selection: ProjectSelectionPlan
) {
  def selectionMode: SelectionMode = selection.mode

  def allReleaseInfos: Seq[ProjectReleaseInfo] =
    allProjects.map(_.toReleaseInfo)

  def orderedReleaseInfos: Seq[ProjectReleaseInfo] =
    orderedProjects.map(_.toReleaseInfo)

  def resolveSelectedProjects(ctx: MonorepoContext): IO[Seq[ProjectPlan]] =
    selection.resolve(ctx)
}
