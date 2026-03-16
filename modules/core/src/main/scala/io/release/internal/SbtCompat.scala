package io.release.internal

import sbt.internal.Aggregation.KeyValue
import sbt.internal.{Act, Aggregation}
import sbt.std.Transform.DummyTaskMap
import sbt.{internal as _, *}

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
}
