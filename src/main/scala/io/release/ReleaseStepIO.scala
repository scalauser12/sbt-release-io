package io.release

import cats.effect.IO
import io.release.vcs.Vcs as ReleaseVcs
import sbt.*
import sbt.Keys.*
import sbt.Def.ScopedKey
import sbtrelease.Compat

/** Keys for command-line parsed attributes stored in State. Delegates to upstream sbt-release
  * attribute keys to ensure interoperability between IO-native steps and upstream steps.
  */
object ReleaseKeys {
  import sbtrelease.ReleasePlugin.autoImport.{ReleaseKeys => UpstreamKeys}

  val useDefaults: AttributeKey[Boolean] = UpstreamKeys.useDefaults
  val skipTests: AttributeKey[Boolean] = UpstreamKeys.skipTests
  val cross: AttributeKey[Boolean] = UpstreamKeys.cross
  val commandLineReleaseVersion: AttributeKey[Option[String]] =
    UpstreamKeys.commandLineReleaseVersion
  val commandLineNextVersion: AttributeKey[Option[String]] = UpstreamKeys.commandLineNextVersion
  val versions: AttributeKey[(String, String)] = UpstreamKeys.versions
}

/** Context threaded through each release step. */
case class ReleaseContext(
    state: State,
    versions: Option[(String, String)] = None, // (releaseVersion, nextVersion)
    vcs: Option[ReleaseVcs] = None,
    skipTests: Boolean = false,
    skipPublish: Boolean = false,
    attributes: Map[String, String] = Map.empty,
    failed: Boolean = false
) {
  def withVersions(release: String, next: String): ReleaseContext =
    copy(versions = Some((release, next)))

  def withVcs(v: ReleaseVcs): ReleaseContext =
    copy(vcs = Some(v))

  def attr(key: String): Option[String] = attributes.get(key)

  def withAttr(key: String, value: String): ReleaseContext =
    copy(attributes = attributes + (key -> value))

  def fail: ReleaseContext = copy(failed = true)
}

/** A single release step with optional check phase and cross-build support. */
case class ReleaseStepIO(
    name: String,
    action: ReleaseContext => IO[ReleaseContext],
    check: ReleaseContext => IO[ReleaseContext] = ctx => IO.pure(ctx),
    enableCrossBuild: Boolean = false
)

object ReleaseStepIO {

  /** Create a step that transforms the context purely. */
  def pure(name: String)(f: ReleaseContext => ReleaseContext): ReleaseStepIO =
    ReleaseStepIO(name, ctx => IO(f(ctx)))

  /** Create a step from a side-effecting function. */
  def io(name: String)(f: ReleaseContext => IO[ReleaseContext]): ReleaseStepIO =
    ReleaseStepIO(name, f)

  /** Implicit conversion from function to anonymous step. */
  import scala.language.implicitConversions
  implicit def functionToStep(f: ReleaseContext => IO[ReleaseContext]): ReleaseStepIO =
    ReleaseStepIO("<anonymous>", f)

  /** Create a step that runs a TaskKey. */
  def fromTask[T](key: TaskKey[T], enableCrossBuild: Boolean = false): ReleaseStepIO =
    ReleaseStepIO(
      name = key.key.label,
      action = ctx =>
        IO {
          val extracted = Project.extract(ctx.state)
          val (newState, _) = extracted.runTask(key, ctx.state)
          ctx.copy(state = newState)
        },
      enableCrossBuild = enableCrossBuild
    )

  /** Create a step that runs a TaskKey aggregated across all subprojects. */
  def fromTaskAggregated[T](key: TaskKey[T], enableCrossBuild: Boolean = false): ReleaseStepIO =
    ReleaseStepIO(
      name = s"${key.key.label} (aggregated)",
      action = ctx =>
        IO {
          val extracted = Project.extract(ctx.state)
          val newState = extracted.runAggregated(key in Global, ctx.state)
          ctx.copy(state = newState)
        },
      enableCrossBuild = enableCrossBuild
    )

  /** Create a step that runs an sbt command. */
  def fromCommand(command: String): ReleaseStepIO =
    ReleaseStepIO(
      name = s"command: $command",
      action = ctx =>
        IO {
          val newState = Command.process(
            command,
            ctx.state,
            (msg: String) => {
              throw new RuntimeException(s"Failed to parse command '$command': $msg")
            }
          )
          ctx.copy(state = newState)
        }
    )

  /** Create a step that runs an sbt command and drains all follow-up commands. Matches upstream
    * sbt-release's releaseStepCommandAndRemaining behavior, which is critical for commands like
    * +publish that enqueue sub-commands.
    */
  def fromCommandAndRemaining(command: String): ReleaseStepIO =
    ReleaseStepIO(
      name = s"command+remaining: $command",
      action = ctx =>
        IO {
          val FailureCommand = Compat.FailureCommand
          val savedRemaining = ctx.state.remainingCommands

          @scala.annotation.tailrec
          def drainCommands(state: State, commandToRun: String): State = {
            val stateWithoutRemaining = state.copy(remainingCommands = Nil)
            val newState = Command.process(
              commandToRun,
              stateWithoutRemaining,
              (msg: String) => {
                throw new RuntimeException(s"Failed to parse command '$commandToRun': $msg")
              }
            )

            newState.remainingCommands.toList match {
              case Nil =>
                // No more commands enqueued, restore saved remaining
                newState.copy(remainingCommands = savedRemaining)
              case head :: tail if head == FailureCommand =>
                // Failure detected, prepend FailureCommand to saved remaining
                newState.copy(remainingCommands = head +: savedRemaining)
              case head :: tail =>
                // More commands enqueued, drain them recursively
                drainCommands(newState.copy(remainingCommands = tail), head.commandLine)
            }
          }

          val finalState = drainCommands(ctx.state, command)
          ctx.copy(state = finalState)
        }
    )

  /** Compose a sequence of steps into a two-phase IO program. */
  def compose(steps: Seq[ReleaseStepIO], crossBuild: Boolean)(
      initialCtx: ReleaseContext
  ): IO[ReleaseContext] = {

    // Phase 1: Run all checks against the same initial state (matching upstream).
    // Upstream: initialChecks.foreach(_(startState))
    // Checks are validation-only; their state mutations are discarded.
    val checkPhase: IO[Unit] = steps.foldLeft(IO.unit) { (acc, step) =>
      acc *> step.check(initialCtx).void
    }

    val FailureCommand = Compat.FailureCommand

    // Set onFailure so sbt injects FailureCommand when a task fails without throwing
    val startCtx = initialCtx.copy(
      state = initialCtx.state.copy(onFailure = Some(FailureCommand))
    )

    // Phase 2: Run actions with failure handling and cross-build support
    def filterFailure(f: ReleaseContext => IO[ReleaseContext])(
        ctx: ReleaseContext
    ): IO[ReleaseContext] = {
      if (ctx.failed) {
        IO.pure(ctx)
      } else {
        // Check if sbt injected FailureCommand (task failure without exception)
        val hasFailure = ctx.state.remainingCommands.headOption.contains(FailureCommand)
        if (hasFailure) {
          IO(ctx.state.log.error("[release-io] Task failure detected via FailureCommand")) *>
            IO.pure(ctx.fail)
        } else {
          f(ctx).handleErrorWith { err =>
            IO(ctx.state.log.error(s"[release-io] Error: ${err.getMessage}")) *>
              IO.pure(ctx.fail)
          }
        }
      }
    }

    /** Between-step hook matching sbt-release 1.4's execution model. Inspects remainingCommands for
      * FailureCommand (sbt's task failure signal), marks the context as failed, and strips the
      * sentinel command.
      */
    def failureCheck(ctx: ReleaseContext): IO[ReleaseContext] = IO {
      val hasFailure = ctx.state.remainingCommands.headOption.contains(FailureCommand)
      if (hasFailure) {
        val cleaned = ctx.state.copy(remainingCommands = ctx.state.remainingCommands.drop(1))
        ctx.copy(state = cleaned, failed = true)
      } else {
        ctx.copy(state = ctx.state.copy(onFailure = Some(FailureCommand)))
      }
    }

    /** Strips the FailureCommand sentinel at the end, matching upstream's removeFailureCommand. */
    def removeFailureCommand(ctx: ReleaseContext): IO[ReleaseContext] = IO {
      ctx.state.remainingCommands.toList match {
        case head :: tail if head == FailureCommand =>
          ctx.copy(state = ctx.state.copy(remainingCommands = tail))
        case _ => ctx
      }
    }

    def buildActionPhase(
        steps: Seq[ReleaseContext => IO[ReleaseContext]]
    )(startCtx: ReleaseContext): IO[ReleaseContext] = {
      val allSteps = steps :+ ((ctx: ReleaseContext) => removeFailureCommand(ctx))
      val interleavedSteps = allSteps.flatMap { step =>
        Seq(filterFailure(step) _, failureCheck _)
      }
      interleavedSteps.foldLeft(IO.pure(startCtx)) { (ioCtx, f) =>
        ioCtx.flatMap(f)
      }
    }

    val wrappedActions: Seq[ReleaseContext => IO[ReleaseContext]] = steps.map { step =>
      val baseAction = (ctx: ReleaseContext) =>
        IO(ctx.state.log.info(s"[release-io] Executing step: ${step.name}")) *> step.action(ctx)

      if (step.enableCrossBuild && crossBuild) { ctx =>
        runCrossBuild(baseAction)(ctx)
      } else {
        baseAction
      }
    }

    // Execute both phases
    checkPhase
      .flatMap { _ =>
        buildActionPhase(wrappedActions)(startCtx)
      }
      .flatMap { finalCtx =>
        if (finalCtx.failed) {
          IO.raiseError(new RuntimeException("Release process failed"))
        } else {
          IO.pure(finalCtx)
        }
      }
  }

  /** Run an action across all crossScalaVersions using proper project reload. Based on
    * sbt-release's implementation which properly switches Scala versions by reloading the project
    * structure, ensuring incremental compilation is invalidated.
    */
  private def runCrossBuild(
      action: ReleaseContext => IO[ReleaseContext]
  )(ctx: ReleaseContext): IO[ReleaseContext] = IO.defer {
    val extracted = Project.extract(ctx.state)
    val crossVersions = extracted.get(crossScalaVersions)
    val currentVersion = (extracted.currentRef / scalaVersion).get(extracted.structure.data)

    if (crossVersions.length <= 1) {
      action(ctx)
    } else {
      val finalIO = crossVersions.foldLeft(IO.pure(ctx)) { (ioCtx, version) =>
        ioCtx.flatMap { currentCtx =>
          IO(currentCtx.state.log.info(s"[release-io] Cross-building with Scala $version")) *>
            IO {
              val newState = switchScalaVersion(currentCtx.state, version)
              currentCtx.copy(state = newState)
            }.flatMap(action)
        }
      }

      // Restore original Scala version after cross-build
      finalIO.flatMap { finalCtx =>
        currentVersion match {
          case Some(ver) =>
            IO {
              val restoredState = switchScalaVersion(finalCtx.state, ver)
              finalCtx.copy(state = restoredState)
            }
          case None => IO.pure(finalCtx)
        }
      }
    }
  }

  /** Switch Scala version by fully reloading the project structure. This is a copy of
    * sbt.Cross.switchVersion logic which ensures incremental compilation caches are properly
    * invalidated.
    */
  private def switchScalaVersion(state: State, version: String): State = {
    val extracted = Project.extract(state)
    import extracted.{*, given}

    state.log.info(s"Setting scala version to $version")

    // Settings to add: set scalaVersion and clear scalaHome
    val add = Seq(
      GlobalScope / Keys.scalaVersion := version,
      GlobalScope / Keys.scalaHome := None
    )

    // Filter out existing scalaVersion and scalaHome settings to avoid conflicts
    val cleared = session.mergeSettings.filterNot(crossExclude)

    // Reapply settings with full project reload
    val newStructure = LoadCompat.reapply(add ++ cleared, structure)
    Project.setProject(session, newStructure, state)
  }

  /** Check if a setting should be excluded during cross-build (scalaVersion, scalaHome). */
  private def crossExclude(s: Setting[_]): Boolean =
    s.key match {
      case ScopedKey(Scope(_, Zero, Zero, _), key)
          if key == Keys.scalaVersion.key || key == Keys.scalaHome.key =>
        true
      case _ => false
    }
}
