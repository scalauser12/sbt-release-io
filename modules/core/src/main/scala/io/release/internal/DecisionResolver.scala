package io.release.internal

import cats.effect.IO
import io.release.ReleaseCtx
import io.release.steps.StepHelpers

/** Shared runtime decision helpers for built-in release flows.
  *
  * The precedence for explicit operator choices is:
  * CLI args -> matching settings -> with-defaults built-in defaults -> prompt adapter -> fallback.
  * CLI/settings are pre-merged into [[ReleaseCtx.decisionDefaults]] at command start.
  */
private[release] object DecisionResolver {

  def resolveVersionInput[C <: ReleaseCtx[C]](
      ctx: C,
      override_ : Option[String],
      suggested: String,
      logPrefix: String,
      prompt: String,
      promptContext: String,
      allowPrompts: Boolean,
      beforePrompt: IO[Unit] = IO.unit
  ): IO[(C, String)] =
    override_.filter(_.nonEmpty) match {
      case Some(versionValue) =>
        StepHelpers.parseVersionInput(versionValue, versionValue).map(ctx -> _)
      case None               =>
        if (!allowPrompts || ctx.useDefaults || !ctx.interactive) IO.pure(ctx -> suggested)
        else
          beforePrompt *>
            PromptAdapter
              .promptLine(ctx, prompt)
              .flatMap {
                case (nextCtx, Some(raw)) =>
                  StepHelpers.parseVersionInput(raw, suggested).map(nextCtx -> _)
                case (nextCtx, None)      =>
                  IO.blocking(
                    nextCtx.state.log.warn(
                      s"$logPrefix ${stdinClosedWhileWaitingWarning(promptContext)}"
                    )
                  ) *> IO.raiseError(
                    new IllegalStateException(stdinClosedWhileWaitingMessage(promptContext))
                  )
              }
    }

  def confirmOrAbort[C <: ReleaseCtx[C]](
      ctx: C,
      configuredAnswer: Option[Boolean],
      logPrefix: String,
      eofContext: String,
      defaultYes: Boolean,
      prompt: String,
      abortMessage: String
  ): IO[C] =
    configuredAnswer
      .orElse(if (ctx.useDefaults) Some(defaultYes) else None) match {
      case Some(true)  => IO.pure(ctx)
      case Some(false) => IO.raiseError(new IllegalStateException(abortMessage))
      case None        =>
        if (!ctx.interactive)
          IO.raiseError(new IllegalStateException(abortMessage))
        else
          PromptAdapter.promptYesNoOrEof(ctx, prompt, defaultYes).flatMap {
            case (nextCtx, Some(true))  => IO.pure(nextCtx)
            case (nextCtx, Some(false)) => IO.raiseError(new IllegalStateException(abortMessage))
            case (nextCtx, None)        =>
              IO.blocking(
                nextCtx.state.log.warn(s"$logPrefix ${stdinClosedBeforeWarning(eofContext)}")
              ) *> IO.raiseError(new IllegalStateException(abortMessage))
          }
    }

  def handleSnapshotDependencies[C <: ReleaseCtx[C]](
      ctx: C,
      deps: Seq[sbt.ModuleID],
      logPrefix: String,
      context: String = ""
  ): IO[C] =
    if (deps.isEmpty) IO.pure(ctx)
    else {
      val depList = deps
        .map(dep => s"  ${dep.organization}:${dep.name}:${dep.revision}")
        .mkString("\n")
      val msg     = s"Snapshot dependencies found$context:\n$depList"

      ctx.decisionDefaults.snapshotDependenciesAnswer
        .orElse(if (ctx.useDefaults) Some(false) else None) match {
        case Some(true)  =>
          IO.blocking(ctx.state.log.warn(s"$logPrefix $msg")).as(ctx)
        case Some(false) =>
          IO.raiseError(
            new IllegalStateException(snapshotDependenciesAbortMessage(context))
          )
        case None        =>
          if (!ctx.interactive)
            IO.raiseError(new IllegalStateException(msg))
          else
            IO.blocking(ctx.state.log.warn(s"$logPrefix $msg")) *>
              confirmOrAbort(
                ctx,
                configuredAnswer = None,
                logPrefix = logPrefix,
                eofContext = s"snapshot dependency confirmation$context",
                defaultYes = false,
                prompt = "Do you want to continue (y/n)? [n] ",
                abortMessage = snapshotDependenciesAbortMessage(context)
              )
      }
    }

  def resolvePushDecision[C <: ReleaseCtx[C]](
      ctx: C,
      logPrefix: String
  )(doPush: C => IO[C], onDeclinePush: C => IO[C]): IO[C] =
    ctx.decisionDefaults.pushAnswer
      .orElse(if (ctx.useDefaults) Some(true) else None) match {
      case Some(true)  => doPush(ctx)
      case Some(false) => onDeclinePush(ctx)
      case None        =>
        if (!ctx.interactive) onDeclinePush(ctx)
        else
          PromptAdapter
            .promptYesNoOrEof(
              ctx,
              prompt = "Push changes to the remote repository (y/n)? [y] ",
              defaultYes = true
            )
            .flatMap {
              case (nextCtx, Some(true))  => doPush(nextCtx)
              case (nextCtx, Some(false)) => onDeclinePush(nextCtx)
              case (nextCtx, None)        =>
                IO.blocking(
                  nextCtx.state.log.warn(
                    s"$logPrefix Standard input closed before push confirmation. Skipping push."
                  )
                ) *> onDeclinePush(nextCtx)
            }
    }

  def resolveTagAnswer[C <: ReleaseCtx[C]](
      ctx: C,
      configuredAnswer: Option[String],
      tagName: String,
      label: String,
      logPrefix: String
  ): IO[(C, String)] =
    configuredAnswer match {
      case Some(answer) => IO.pure(ctx -> answer)
      case None         =>
        if (ctx.useDefaults)
          IO.blocking(
            ctx.state.log.warn(
              s"$logPrefix Tag [$tagName] already exists${forLabel(label)}. Aborting (use-defaults mode)."
            )
          ).as(ctx -> "a")
        else if (!ctx.interactive)
          IO.raiseError(
            new IllegalStateException(
              s"Tag [$tagName] already exists${forLabel(label)}. Aborting release in non-interactive mode."
            )
          )
        else
          PromptAdapter
            .promptLine(
              ctx,
              s"Tag [$tagName] exists${forLabel(label)}! " +
                "Overwrite, keep or abort or enter a new tag (o/k/a)? [a] "
            )
            .flatMap {
              case (nextCtx, Some(raw)) => IO.pure(nextCtx -> raw)
              case (nextCtx, None)      =>
                IO.blocking(
                  nextCtx.state.log.warn(
                    s"$logPrefix ${stdinClosedBeforeWarning(s"tag conflict resolution${forLabel(label)}")}"
                  )
                ).as(nextCtx -> "a")
            }
    }

  private def forLabel(label: String): String =
    if (label.isEmpty) "" else s" for $label"

  private def stdinClosedBeforeWarning(context: String): String =
    s"Standard input closed before $context. Aborting."

  private def stdinClosedWhileWaitingMessage(context: String): String =
    s"Standard input closed while waiting for $context."

  private def stdinClosedWhileWaitingWarning(context: String): String =
    s"${stdinClosedWhileWaitingMessage(context)} Aborting."

  private def snapshotDependenciesAbortMessage(context: String): String =
    "Aborting release due to snapshot dependencies" +
      s"$context. To continue in non-interactive or with-defaults runs, " +
      "pass 'default-snapshot-dependencies-answer y'."
}
