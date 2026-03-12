package io.release.internal

import io.release.ReleaseIO.{releaseIOTagComment, releaseIOTagName, releaseIOVcsSign}
import sbt.State

/** Resolves tagging inputs for built-in steps from the current sbt state. */
private[release] object CoreTagResolver {

  /** Resolves tag plan from sbt state. Performs blocking sbt task evaluation;
    * callers must wrap in `IO.blocking`.
    */
  def resolve(state: State): TagPlan = {
    val (s1, tagName)    = SbtRuntime.runTask(state, releaseIOTagName)
    val (s2, tagComment) = SbtRuntime.runTask(s1, releaseIOTagComment)

    TagPlan(
      state = s2,
      tagName = tagName,
      tagComment = tagComment,
      sign = SbtRuntime.getSetting(s2, releaseIOVcsSign),
      defaultAnswer = CoreReleasePlan.current(s2).flatMap(_.tagDefault)
    )
  }

}
