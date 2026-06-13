package io.release.runtime.workflow

import io.release.ReleaseSharedKeys
import io.release.runtime.ReleaseDecisionDefaults
import _root_.sbt.{internal as _, *}

private[release] object DecisionDefaultsSupport {

  final case class CliExtractors[A](
      tagExists: PartialFunction[A, String],
      snapshotDependencies: PartialFunction[A, Boolean],
      remoteCheckFailure: PartialFunction[A, Boolean],
      upstreamBehind: PartialFunction[A, Boolean],
      push: PartialFunction[A, Boolean]
  )

  def renderYesNo(value: Boolean): String =
    if (value) "y" else "n"

  def resolveFromArgs[A](
      state: State,
      prefix: String,
      args: Seq[A],
      extractors: CliExtractors[A],
      warnOnDuplicates: Boolean
  ): ReleaseDecisionDefaults = {
    val extracted = Project.extract(state)

    // Build-configured defaults read directly from the grouped `releaseIODefaults*` keys.
    val settings = ReleaseDecisionDefaults(
      tagExistsAnswer =
        extracted.getOpt(ReleaseSharedKeys.releaseIODefaultsTagExistsAnswer).flatten,
      snapshotDependenciesAnswer =
        extracted.getOpt(ReleaseSharedKeys.releaseIODefaultsSnapshotDependenciesAnswer).flatten,
      remoteCheckFailureAnswer =
        extracted.getOpt(ReleaseSharedKeys.releaseIODefaultsRemoteCheckFailureAnswer).flatten,
      upstreamBehindAnswer =
        extracted.getOpt(ReleaseSharedKeys.releaseIODefaultsUpstreamBehindAnswer).flatten,
      pushAnswer = extracted.getOpt(ReleaseSharedKeys.releaseIODefaultsPushAnswer).flatten
    )

    // CLI overrides win over build-configured defaults (`merge` prefers `cli`).
    val cli = ReleaseDecisionDefaults(
      tagExistsAnswer = resolveLast(
        state,
        prefix,
        "default-tag-exists-answer",
        args.collect(extractors.tagExists),
        identity[String],
        warnOnDuplicates
      ),
      snapshotDependenciesAnswer = resolveLast(
        state,
        prefix,
        "default-snapshot-dependencies-answer",
        args.collect(extractors.snapshotDependencies),
        renderYesNo,
        warnOnDuplicates
      ),
      remoteCheckFailureAnswer = resolveLast(
        state,
        prefix,
        "default-remote-check-failure-answer",
        args.collect(extractors.remoteCheckFailure),
        renderYesNo,
        warnOnDuplicates
      ),
      upstreamBehindAnswer = resolveLast(
        state,
        prefix,
        "default-upstream-behind-answer",
        args.collect(extractors.upstreamBehind),
        renderYesNo,
        warnOnDuplicates
      ),
      pushAnswer = resolveLast(
        state,
        prefix,
        "default-push-answer",
        args.collect(extractors.push),
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
