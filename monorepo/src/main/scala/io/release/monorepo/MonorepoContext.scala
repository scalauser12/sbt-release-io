package io.release.monorepo

import io.release.{ReleaseContext, ReleaseKeys}
import sbt.*
import sbtrelease.Vcs

/** Identifies which subprojects participate in a monorepo release. */
case class ProjectReleaseInfo(
    ref: ProjectRef,
    name: String,
    baseDir: File,
    versionFile: File,
    versions: Option[(String, String)] = None, // (releaseVersion, nextVersion)
    tagName: Option[String] = None,
    failed: Boolean = false
)

/** Tagging strategy for monorepo releases. */
sealed trait MonorepoTagStrategy
object MonorepoTagStrategy {

  /** Each subproject gets its own tag (e.g., core-v1.2.0, api-v0.5.0). */
  case object PerProject extends MonorepoTagStrategy

  /** A single tag covers the entire release (e.g., v1.2.0). */
  case object Unified extends MonorepoTagStrategy
}

/** Context threaded through each monorepo release step. */
case class MonorepoContext(
    state: State,
    vcs: Option[Vcs] = None,
    projects: Seq[ProjectReleaseInfo] = Seq.empty, // topologically sorted
    skipTests: Boolean = false,
    skipPublish: Boolean = false,
    interactive: Boolean = false,
    tagStrategy: MonorepoTagStrategy = MonorepoTagStrategy.PerProject,
    attributes: Map[String, String] = Map.empty,
    failed: Boolean = false
) {

  def currentProjects: Seq[ProjectReleaseInfo] =
    projects.filterNot(_.failed)

  def updateProject(ref: ProjectRef)(f: ProjectReleaseInfo => ProjectReleaseInfo): MonorepoContext =
    copy(projects = projects.map(p => if (p.ref == ref) f(p) else p))

  def withState(s: State): MonorepoContext = copy(state = s)

  def withVcs(v: Vcs): MonorepoContext = copy(vcs = Some(v))

  def withProjects(ps: Seq[ProjectReleaseInfo]): MonorepoContext = copy(projects = ps)

  def attr(key: String): Option[String] = attributes.get(key)

  def withAttr(key: String, value: String): MonorepoContext =
    copy(attributes = attributes + (key -> value))

  def fail: MonorepoContext = copy(failed = true)

  /** Convert to a single-project ReleaseContext for reusing core steps. */
  def toReleaseContext: ReleaseContext =
    ReleaseContext(
      state = state,
      versions = None,
      vcs = vcs,
      skipTests = skipTests,
      skipPublish = skipPublish,
      interactive = interactive
    )

  /** Absorb state changes from a ReleaseContext back into this MonorepoContext. */
  def fromReleaseContext(ctx: ReleaseContext): MonorepoContext =
    copy(state = ctx.state, vcs = ctx.vcs)
}
