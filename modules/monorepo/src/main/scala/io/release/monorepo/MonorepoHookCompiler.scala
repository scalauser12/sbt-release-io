package io.release.monorepo

import io.release.internal.ProcessStep
import sbt.State

/** Compiles semantic monorepo hook settings into the existing monorepo engine. */
private[monorepo] object MonorepoHookCompiler {

  def resolve(state: State): MonorepoHookConfiguration =
    MonorepoHookConfiguration.resolve(state)

  def compile(state: State): Seq[ProcessStep[MonorepoContext, ProjectReleaseInfo]] =
    compile(resolve(state))

  def compile(
      hooks: MonorepoHookConfiguration
  ): Seq[ProcessStep[MonorepoContext, ProjectReleaseInfo]] =
    MonorepoLifecycle.compile(hooks)
}
