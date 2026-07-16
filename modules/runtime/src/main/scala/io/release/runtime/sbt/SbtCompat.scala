package io.release.runtime.sbt

import _root_.sbt.internal.Act
import _root_.sbt.internal.Aggregation
import _root_.sbt.internal.Aggregation.KeyValue
import _root_.sbt.std.Transform.DummyTaskMap
import _root_.sbt.{internal as _, *}

private[release] object SbtCompat {

  val FailureCommand: Exec = Exec("--failure--", None, None)

  def runTaskAggregated[T](
      taskKey: TaskKey[T],
      state: State
  ): (State, Result[Seq[KeyValue[T]]]) = {
    import EvaluateTask.*

    val extra     = DummyTaskMap(Nil)
    val extracted = Project.extract(state)
    val config    = extractedTaskConfig(extracted, extracted.structure, state)

    val rkey  = Project.mapScope(
      Scope.resolveScope(GlobalScope, extracted.currentRef.build, extracted.rootProject)
    )(taskKey.scopedKey)
    val keys  = Aggregation.aggregate(rkey, ScopeMask(), extracted.structure.extra)
    val tasks = Act.keyValues(extracted.structure)(keys)
    val toRun = tasks.map { case KeyValue(k, t) => t.map(v => KeyValue(k, v)) }.join
    val roots = tasks.map { case KeyValue(k, _) => k }

    withStreams(extracted.structure, state) { str =>
      val transform = nodeView(state, str, roots, extra)
      runTask(toRun, state, str, extracted.structure.index.triggers, config)(using transform)
    }
  }

  /** Run one aggregated task graph containing only the requested projects.
    *
    * The ordinary `Extracted.runAggregated` expands from the root and runs every resolved
    * aggregate target. Core publish validation may authorize only a subset of that expansion,
    * so filter the expanded roots before handing them to `Aggregation.runTasks`. Keeping one
    * task graph is important: shared dependencies are evaluated once even when several selected
    * project actions depend on them.
    *
    * Every requested project must both belong to the task's aggregate expansion and resolve the
    * task. `Act.keyValues` normally drops unresolved keys; fail before starting the graph instead
    * so a selected publish target cannot disappear silently.
    */
  def runTaskAggregatedForProjects[T](
      state: State,
      taskKey: TaskKey[T],
      projects: Seq[ProjectRef]
  ): State =
    if (projects.isEmpty) state
    else {
      val extra            = DummyTaskMap(Nil)
      val extracted        = Project.extract(state)
      val distinctProjects = projects.distinct
      val rootKey          = Project.mapScope(
        Scope.resolveScope(GlobalScope, extracted.currentRef.build, extracted.rootProject)
      )((extracted.currentRef / taskKey).scopedKey)
      val expandedKeys     = Aggregation.aggregate(rootKey, ScopeMask(), extracted.structure.extra)
      val selected         = distinctProjects.map { project =>
        project -> expandedKeys.find(_.scope.project.toOption.contains(project))
      }
      val outsideExpansion = selected.collect { case (project, None) => project }
      val selectedKeys     = selected.flatMap(_._2)
      val tasks            = Act.keyValues(extracted.structure)(selectedKeys)
      val resolvedKeys     = tasks.iterator.map(_.key).toSet
      val undefined        = selected.collect {
        case (project, Some(key)) if !resolvedKeys.contains(key) => project
      }
      val unresolved       = outsideExpansion ++ undefined
      val unresolvedDetail = Seq(
        if (outsideExpansion.nonEmpty)
          Some(s"outside aggregate expansion: ${outsideExpansion.map(_.project).mkString(", ")}")
        else None,
        if (undefined.nonEmpty)
          Some(s"task undefined: ${undefined.map(_.project).mkString(", ")}")
        else None
      ).flatten.mkString("; ", "; ", "")

      if (unresolved.nonEmpty)
        throw new IllegalStateException(
          s"Aggregated task '${taskKey.key.label}' is unresolved for eligible projects: " +
            unresolved.map(_.project).mkString(", ") + unresolvedDetail
        )

      Aggregation.runTasks(
        state,
        tasks,
        extra,
        show = Aggregation.defaultShow(state, false)
      )(using extracted.showKey)
    }
}
