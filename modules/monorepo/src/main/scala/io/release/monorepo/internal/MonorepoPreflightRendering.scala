package io.release.monorepo.internal

import io.release.runtime.command.CheckModeOutput
import io.release.runtime.preflight.Evaluation
import io.release.runtime.preflight.PreflightRendering

/** Pure rendering helpers for the monorepo preflight summary. Keeps `MonorepoPreflight` focused on
  * orchestration; this file owns label widths, indentation, and per-evaluation text formatting.
  */
private[internal] object MonorepoPreflightRendering {

  // Width fits the longest monorepo summary label ("selection mode"). Core uses 15 in
  // CorePreflight.SummaryLabelWidth — the constants are intentionally module-local.
  private val SummaryLabelWidth = 14

  def pad(label: String): String          = PreflightRendering.pad(label, SummaryLabelWidth)
  def padLabelOnly(label: String): String =
    PreflightRendering.padLabelOnly(label, SummaryLabelWidth)

  def renderSummary(summary: MonorepoPreflight.Summary): List[String] =
    List(
      "Preflight summary:",
      pad("selection mode") + renderSelectionMode(summary.selectionMode),
      pad("cross-build") + CheckModeOutput.enabled(summary.crossBuildEnabled),
      pad("publish") + summary.publishSummary,
      pad("push") + summary.pushSummary,
      pad("steps") + summary.stepNames.mkString(" -> ")
    ) ++ renderProjectSection(summary.projects)

  def renderSelectionMode(mode: Evaluation[SelectionMode]): String =
    renderEvaluation(mode) {
      case SelectionMode.ExplicitSelection => "explicit selection"
      case SelectionMode.AllChanged        => "all changed"
      case SelectionMode.DetectChanges     => "detect changes"
    }

  def renderProjectSection(
      projects: Evaluation[Seq[MonorepoPreflight.ProjectSummary]]
  ): List[String] =
    projects match {
      case Evaluation.Resolved(resolvedProjects) =>
        padLabelOnly("projects") :: resolvedProjects.map(renderProject).toList
      case Evaluation.NotEvaluated(reason)       =>
        List(pad("projects") + s"not evaluated ($reason)")
    }

  private def renderProject(project: MonorepoPreflight.ProjectSummary): String = {
    val versionText = renderEvaluation(project.versions)(
      v => s"release ${v.releaseVersion}, next ${v.nextVersion}",
      reason => s"release/next not evaluated ($reason)"
    )
    val tagText     = renderEvaluation(project.tag)(
      t => s"tag ${t.tagName} (${t.tagStatus})",
      reason => s"tag not evaluated ($reason)"
    )
    s"    - ${project.name}: $versionText, $tagText"
  }

  private def renderEvaluation[A](
      evaluation: Evaluation[A]
  )(
      onResolved: A => String,
      onNotEvaluated: String => String = reason => s"not evaluated ($reason)"
  ): String =
    evaluation match {
      case Evaluation.Resolved(value)      => onResolved(value)
      case Evaluation.NotEvaluated(reason) => onNotEvaluated(reason)
    }
}
