package io.release

import cats.effect.IO
import io.release.vcs.Vcs as ReleaseVcs
import sbt.*
import sbt.Keys.*

/**
 * Keys for command-line parsed attributes stored in State.
 * Delegates to upstream sbt-release attribute keys to ensure interoperability
 * between IO-native steps and upstream steps.
 */
object ReleaseKeys {
  import sbtrelease.ReleasePlugin.autoImport.{ReleaseKeys => UpstreamKeys}

  val useDefaults: AttributeKey[Boolean] = UpstreamKeys.useDefaults
  val skipTests: AttributeKey[Boolean] = UpstreamKeys.skipTests
  val cross: AttributeKey[Boolean] = UpstreamKeys.cross
  val commandLineReleaseVersion: AttributeKey[Option[String]] = UpstreamKeys.commandLineReleaseVersion
  val commandLineNextVersion: AttributeKey[Option[String]] = UpstreamKeys.commandLineNextVersion
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
      action = ctx => IO {
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
      action = ctx => IO {
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
      action = ctx => IO {
        val newState = Command.process(command, ctx.state, (msg: String) => {
          throw new RuntimeException(s"Failed to parse command '$command': $msg")
        })
        ctx.copy(state = newState)
      }
    )

  /** Create a step that runs an sbt command and preserves remaining commands. */
  def fromCommandAndRemaining(command: String): ReleaseStepIO =
    ReleaseStepIO(
      name = s"command+remaining: $command",
      action = ctx => IO {
        val remainingCommands = ctx.state.remainingCommands
        val stateWithoutRemaining = ctx.state.copy(remainingCommands = Nil)
        val newState = Command.process(command, stateWithoutRemaining, (msg: String) => {
          throw new RuntimeException(s"Failed to parse command '$command': $msg")
        })
        val stateWithRemaining = newState.copy(remainingCommands = remainingCommands)
        ctx.copy(state = stateWithRemaining)
      }
    )

  /** Compose a sequence of steps into a two-phase IO program. */
  def compose(steps: Seq[ReleaseStepIO], crossBuild: Boolean)(
      initialCtx: ReleaseContext
  ): IO[ReleaseContext] = {

    // Phase 1: Run all checks sequentially, threading the context
    val checkPhase: IO[ReleaseContext] = steps.foldLeft(IO.pure(initialCtx)) { (acc, step) =>
      acc.flatMap { ctx =>
        step.check(ctx)
      }
    }

    // Phase 2: Run actions with failure handling and cross-build support
    def filterFailure(f: ReleaseContext => IO[ReleaseContext])(
        ctx: ReleaseContext
    ): IO[ReleaseContext] = {
      if (ctx.failed) {
        IO.pure(ctx)
      } else {
        f(ctx).handleErrorWith { err =>
          IO(ctx.state.log.error(s"[release-io] Error: ${err.getMessage}")) *>
            IO.pure(ctx.fail)
        }
      }
    }

    def buildActionPhase(steps: Seq[ReleaseContext => IO[ReleaseContext]])(startCtx: ReleaseContext): IO[ReleaseContext] = {
      steps.foldLeft(IO.pure(startCtx)) { (ioCtx, step) =>
        ioCtx.flatMap(filterFailure(step))
      }
    }

    val wrappedActions: Seq[ReleaseContext => IO[ReleaseContext]] = steps.map { step =>
      val baseAction = (ctx: ReleaseContext) =>
        IO(ctx.state.log.info(s"[release-io] Executing step: ${step.name}")) *> step.action(ctx)

      if (step.enableCrossBuild && crossBuild) {
        ctx => runCrossBuild(baseAction)(ctx)
      } else {
        baseAction
      }
    }

    // Execute both phases
    checkPhase.flatMap { checkedCtx =>
      buildActionPhase(wrappedActions)(checkedCtx)
    }.flatMap { finalCtx =>
      if (finalCtx.failed) {
        IO.raiseError(new RuntimeException("Release process failed"))
      } else {
        IO.pure(finalCtx)
      }
    }
  }

  /** Run an action across all crossScalaVersions. */
  private def runCrossBuild(
      action: ReleaseContext => IO[ReleaseContext]
  )(ctx: ReleaseContext): IO[ReleaseContext] = IO.defer {
    val extracted = Project.extract(ctx.state)
    val crossVersions = extracted.get(crossScalaVersions)

    if (crossVersions.length <= 1) {
      action(ctx)
    } else {
      crossVersions.foldLeft(IO.pure(ctx)) { (ioCtx, version) =>
        ioCtx.flatMap { currentCtx =>
          IO(currentCtx.state.log.info(s"[release-io] Cross-building with Scala $version")) *>
            IO {
              val newState = extracted.appendWithSession(
                Seq(ThisBuild / scalaVersion := version),
                currentCtx.state
              )
              currentCtx.copy(state = newState)
            }.flatMap(action)
        }
      }
    }
  }
}
