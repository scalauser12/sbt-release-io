package io.release.monorepo

import _root_.io.release.ReleaseCtx
import sbt.*
import sbtrelease.Vcs

/** Metadata for a single subproject participating in a monorepo release.
  *
  * Created by [[MonorepoReleasePluginLike]] during argument validation, then threaded
  * through [[MonorepoStepIO.PerProject]] steps. Per-project failure is tracked here
  * independently of the global [[MonorepoContext.failed]] flag.
  *
  * @param ref         sbt project reference
  * @param name        project name (matches `ref.project`)
  * @param baseDir     project root directory
  * @param versionFile path to the project's `version.sbt`
  * @param versions    `(releaseVersion, nextVersion)` pair, set by version inquiry steps
  * @param tagName     VCS tag for this project's release, set by the tagging step
  * @param failed      set to true when this project's step action fails
  */
case class ProjectReleaseInfo(
    ref: ProjectRef,
    name: String,
    baseDir: File,
    versionFile: File,
    versions: Option[(String, String)] = None, // (releaseVersion, nextVersion)
    tagName: Option[String] = None,
    failed: Boolean = false
) {
  def releaseVersion: Option[String] = versions.map(_._1)
  def nextVersion: Option[String]    = versions.map(_._2)
}

/** Tagging strategy for monorepo releases. */
sealed trait MonorepoTagStrategy
object MonorepoTagStrategy {

  /** Each subproject gets its own tag (e.g., core-v1.2.0, api-v0.5.0). */
  case object PerProject extends MonorepoTagStrategy

  /** A single tag covers the entire release (e.g., v1.2.0). */
  case object Unified extends MonorepoTagStrategy
}

/** Immutable context threaded through each monorepo release step during both phases.
  *
  * Created by [[MonorepoReleasePluginLike.doMonorepoRelease]], then passed through
  * the composer. Global steps receive the context directly; per-project
  * steps receive both the context and the current [[ProjectReleaseInfo]].
  *
  * @param state       the current `sbt.State`, updated between steps
  * @param vcs         VCS adapter (git), set by `initializeVcs`
  * @param projects    subprojects in topological order
  * @param skipTests   when true, test steps are skipped
  * @param skipPublish when true, publish steps are skipped
  * @param interactive when true, steps may prompt for user input
  * @param tagStrategy per-project or unified tagging
  * @param attributes  arbitrary key-value store for inter-step communication
  * @param failed      set to true by the composer on step failure; subsequent steps are skipped
  */
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
) extends ReleaseCtx[MonorepoContext] {

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
}
