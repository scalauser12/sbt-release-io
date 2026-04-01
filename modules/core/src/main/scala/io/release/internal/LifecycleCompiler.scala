package io.release.internal

private[release] object LifecycleCompiler {

  sealed trait Phase[Config, Step] {
    def rawSteps: Seq[Step]
    def compile(config: Config): Seq[Step]
  }

  final case class BuiltInPhase[Config, Step](
      step: Step,
      enabled: Config => Boolean = (_: Config) => true
  ) extends Phase[Config, Step] {
    override val rawSteps: Seq[Step] = Seq(step)

    override def compile(config: Config): Seq[Step] =
      if (enabled(config)) Seq(step) else Seq.empty
  }

  final case class HookPhase[Config, Hook, Step](
      phase: String,
      resolveHooks: Config => Seq[Hook],
      buildSteps: (String, Seq[Hook]) => Seq[Step],
      enabled: Config => Boolean = (_: Config) => true
  ) extends Phase[Config, Step] {
    override val rawSteps: Seq[Step] = Seq.empty

    override def compile(config: Config): Seq[Step] =
      if (enabled(config)) buildSteps(phase, resolveHooks(config))
      else Seq.empty
  }

  def defaults[Config, Step](phases: Seq[Phase[Config, Step]]): Seq[Step] =
    phases.flatMap(_.rawSteps)

  def compile[Config, Step](
      config: Config,
      phases: Seq[Phase[Config, Step]]
  ): Seq[Step] =
    phases.flatMap(_.compile(config))
}
