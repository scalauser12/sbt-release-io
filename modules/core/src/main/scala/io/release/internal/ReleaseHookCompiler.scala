package io.release.internal

import sbt.State

/** Compiles semantic core hook settings into the existing linear release engine. */
private[release] object ReleaseHookCompiler {

  def resolve(state: State): CoreHookConfiguration =
    CoreHookConfiguration.resolve(state)

  def compile(state: State): Seq[ProcessStep.Single[io.release.ReleaseContext]] =
    compile(resolve(state))

  def compile(hooks: CoreHookConfiguration): Seq[ProcessStep.Single[io.release.ReleaseContext]] =
    CoreLifecycle.compile(hooks)
}
