package io.release.internal

import io.release.ReleaseKeys
import io.release.steps.StepHelpers.useDefaults
import sbt.State
import sbtrelease.ReleasePlugin.autoImport.{releaseTagComment, releaseTagName, releaseVcsSign}

/** Resolves tagging inputs for built-in steps from the current sbt state. */
private[release] object CoreTagResolver {

  def resolve(state: State): TagPlan = {
    val (s1, tagName)    = SbtRuntime.runTask(state, releaseTagName)
    val (s2, tagComment) = SbtRuntime.runTask(s1, releaseTagComment)

    TagPlan(
      state = s2,
      tagName = tagName,
      tagComment = tagComment,
      sign = SbtRuntime.getSetting(s2, releaseVcsSign),
      defaultAnswer = CoreReleasePlanner
        .current(s2)
        .flatMap(_.tagDefault)
        .orElse(
          s2.get(ReleaseKeys.tagDefault).flatten
        )
    )
  }

  def useDefaultsFor(plan: TagPlan): Boolean =
    useDefaults(plan.state)
}
