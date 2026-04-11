package io.release.runtime.workflow

import _root_.sbt.{Extracted, SettingKey, State}
import io.release.runtime.ReleaseDecisionDefaults

private[release] object DecisionDefaultsSupport {

  final case class DefaultSettingKeys(
      tagExists: SettingKey[Option[String]],
      snapshotDependencies: SettingKey[Option[Boolean]],
      remoteCheckFailure: SettingKey[Option[Boolean]],
      upstreamBehind: SettingKey[Option[Boolean]],
      push: SettingKey[Option[Boolean]]
  )

  final case class CliInputs(
      tagExistsAnswers: Seq[String] = Nil,
      snapshotDependenciesAnswers: Seq[Boolean] = Nil,
      remoteCheckFailureAnswers: Seq[Boolean] = Nil,
      upstreamBehindAnswers: Seq[Boolean] = Nil,
      pushAnswers: Seq[Boolean] = Nil
  )

  def renderYesNo(value: Boolean): String =
    if (value) "y" else "n"

  def settingsFromExtracted(
      extracted: Extracted,
      keys: DefaultSettingKeys
  ): ReleaseDecisionDefaults =
    ReleaseDecisionDefaults(
      tagExistsAnswer = extracted.getOpt(keys.tagExists).flatten,
      snapshotDependenciesAnswer = extracted.getOpt(keys.snapshotDependencies).flatten,
      remoteCheckFailureAnswer = extracted.getOpt(keys.remoteCheckFailure).flatten,
      upstreamBehindAnswer = extracted.getOpt(keys.upstreamBehind).flatten,
      pushAnswer = extracted.getOpt(keys.push).flatten
    )

  def resolve(
      state: State,
      prefix: String,
      settings: ReleaseDecisionDefaults,
      cliInputs: CliInputs,
      warnOnDuplicates: Boolean
  ): ReleaseDecisionDefaults = {
    val cli = ReleaseDecisionDefaults(
      tagExistsAnswer = resolveLast(
        state,
        prefix,
        "default-tag-exists-answer",
        cliInputs.tagExistsAnswers,
        identity[String],
        warnOnDuplicates
      ),
      snapshotDependenciesAnswer = resolveLast(
        state,
        prefix,
        "default-snapshot-dependencies-answer",
        cliInputs.snapshotDependenciesAnswers,
        renderYesNo,
        warnOnDuplicates
      ),
      remoteCheckFailureAnswer = resolveLast(
        state,
        prefix,
        "default-remote-check-failure-answer",
        cliInputs.remoteCheckFailureAnswers,
        renderYesNo,
        warnOnDuplicates
      ),
      upstreamBehindAnswer = resolveLast(
        state,
        prefix,
        "default-upstream-behind-answer",
        cliInputs.upstreamBehindAnswers,
        renderYesNo,
        warnOnDuplicates
      ),
      pushAnswer = resolveLast(
        state,
        prefix,
        "default-push-answer",
        cliInputs.pushAnswers,
        renderYesNo,
        warnOnDuplicates
      )
    )

    ReleaseDecisionDefaults.merge(cli, settings)
  }

  def resolveLast[A](
      state: State,
      prefix: String,
      argName: String,
      matches: Seq[A],
      render: A => String,
      warnOnDuplicates: Boolean = true
  ): Option[A] = {
    val selected = matches.lastOption

    if (warnOnDuplicates && matches.size > 1)
      state.log.warn(
        s"$prefix Multiple $argName args provided; using '${selected.map(render).getOrElse("<unknown>")}'"
      )

    selected
  }
}
