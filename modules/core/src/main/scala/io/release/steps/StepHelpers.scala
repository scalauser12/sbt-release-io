package io.release.steps

import cats.effect.IO
import io.release.ReleaseContext
import io.release.ReleaseCtx
import io.release.vcs.Vcs
import io.release.version.Version
import sbt.EvaluateTask
import sbt.Incomplete
import sbt.Result
import sbt.internal.Aggregation.KeyValue

import java.io.ByteArrayOutputStream
import java.io.InputStream
import java.nio.charset.Charset
import scala.sys.process.*

/** Shared helpers used across release step objects. */
private[release] object StepHelpers {

  def errorMessage(err: Throwable): String =
    Option(err.getMessage).getOrElse(err.toString)

  /** Read a line from standard input without reading ahead into later answers.
    *
    * The reader tracks only the current `System.in` identity and enough CRLF state to avoid
    * turning `\r\n` into an empty extra line on the next `readLine()` call. Returns `null`
    * on EOF when no bytes were read for the current line.
    */
  private[release] def readLine(): IO[String] =
    IO.blocking(stdinLineReader.readLine())

  /** Read a line from standard input and fail fast if stdin closes before input arrives. */
  private[release] def readRequiredLine(context: String): IO[String] =
    readLine().flatMap {
      case null  =>
        IO.raiseError(
          new IllegalStateException(s"Standard input closed while waiting for $context.")
        )
      case input => IO.pure(input)
    }

  private[release] def askYesNo(prompt: String, defaultYes: Boolean): IO[Boolean] =
    askYesNoOrEof(prompt, defaultYes).map(_.getOrElse(defaultYes))

  private[release] def askYesNoOrEof(
      prompt: String,
      defaultYes: Boolean
  ): IO[Option[Boolean]] =
    IO.print(prompt) *>
      readLine().map {
        case null  => None
        case input => Some(parseYesNoInput(input, defaultYes))
      }

  private[this] val stdinLineReader = new StdinLineReader

  private final class StdinLineReader {
    private[this] val stdinCharset = Charset.defaultCharset()

    @volatile private var cachedIn: InputStream  = _
    @volatile private var skipLeadingLf: Boolean = false

    private def currentInput(): InputStream = {
      val currentIn = System.in
      if (currentIn ne cachedIn) {
        cachedIn = currentIn
        skipLeadingLf = false
      }
      currentIn
    }

    private def decode(buffer: ByteArrayOutputStream): String =
      new String(buffer.toByteArray, stdinCharset)

    def readLine(): String = synchronized {
      val currentIn = currentInput()
      val buffer    = new ByteArrayOutputStream()

      while (true) {
        val nextByte = currentIn.read()

        if (skipLeadingLf && nextByte == '\n') {
          skipLeadingLf = false
        } else {
          skipLeadingLf = false

          nextByte match {
            case -1   =>
              return if (buffer.size() == 0) null else decode(buffer)
            case '\n' =>
              return decode(buffer)
            case '\r' =>
              skipLeadingLf = true
              return decode(buffer)
            case byte =>
              buffer.write(byte)
          }
        }
      }

      null
    }
  }

  def useDefaults[C <: ReleaseCtx[C]](ctx: C): Boolean =
    ctx.useDefaults

  def required[A, B](opt: Option[A], error: String)(f: A => IO[B]): IO[B] =
    opt.fold(IO.raiseError[B](new IllegalStateException(error)))(f)

  def requireVcs(ctx: ReleaseContext)(f: Vcs => IO[ReleaseContext]): IO[ReleaseContext] =
    required(ctx.vcs, "VCS not initialized. Ensure initializeVcs runs before this step.")(f)

  def requireVersions(
      ctx: ReleaseContext
  )(f: ((String, String)) => IO[ReleaseContext]): IO[ReleaseContext] =
    required(ctx.versions, "Versions not set. Ensure inquireVersions runs before this step.")(f)

  /** Runs a process inside IO.blocking and raises on non-zero exit. */
  def runProcess(process: ProcessBuilder, context: => String): IO[Unit] =
    IO.blocking(process.!).flatMap { code =>
      if (code != 0)
        IO.raiseError(new IllegalStateException(s"$context failed with exit code $code"))
      else
        IO.unit
    }

  private def parseYesNoInput(raw: String, defaultYes: Boolean): Boolean =
    raw.trim.toLowerCase match {
      case ""          => defaultYes
      case "y" | "yes" => true
      case _           => false
    }

  /** Confirmation prompt shared by core and monorepo steps.
    * `useDefaults` is supplied explicitly so the helper does not depend on `sbt.State`
    * for startup-only execution flags.
    */
  def confirmContinue(
      state: sbt.State,
      interactive: Boolean,
      useDefaults: Boolean,
      prompt: String,
      defaultYes: Boolean,
      abortMessage: String
  ): IO[Unit] = {
    if (!interactive)
      IO.raiseError(new IllegalStateException(abortMessage))
    else {
      val decisionIO =
        if (useDefaults) IO.pure(defaultYes)
        else askYesNo(prompt, defaultYes = defaultYes)

      decisionIO.flatMap { continue =>
        if (continue) IO.unit
        else IO.raiseError(new IllegalStateException(abortMessage))
      }
    }
  }

  def confirmContinue(
      ctx: ReleaseContext,
      prompt: String,
      defaultYes: Boolean,
      abortMessage: String
  ): IO[Unit] =
    confirmContinue(
      ctx.state,
      ctx.interactive,
      useDefaults(ctx),
      prompt,
      defaultYes,
      abortMessage
    )

  /** Parse raw version input: trim whitespace, return default if empty, validate otherwise. */
  def parseVersionInput(raw: String, default: String): IO[String] = {
    val input = Option(raw).map(_.trim).getOrElse("")
    if (input.isEmpty) IO.pure(default)
    else
      IO.fromOption(Version(input).map(_.render))(
        new IllegalArgumentException(
          s"Invalid version format: '$input'. " +
            "Use values like '1.2.3' or '1.2.4-SNAPSHOT'. See the command help for examples."
        )
      )
  }

  /** Handle snapshot dependencies found during validation. Shared by core and monorepo.
    * The caller provides `useDefaults` from the threaded context runtime metadata.
    */
  def handleSnapshotDependencies(
      deps: Seq[sbt.ModuleID],
      state: sbt.State,
      interactive: Boolean,
      useDefaults: Boolean,
      logPrefix: String,
      context: String = ""
  ): IO[Unit] = {
    if (deps.isEmpty) IO.unit
    else {
      val depList = deps
        .map(dep => s"  ${dep.organization}:${dep.name}:${dep.revision}")
        .mkString("\n")
      val msg     = s"Snapshot dependencies found$context:\n$depList"

      if (!interactive)
        IO.raiseError[Unit](new IllegalStateException(msg))
      else
        IO.blocking(state.log.warn(s"$logPrefix $msg")) *>
          confirmContinue(
            state,
            interactive,
            useDefaults,
            prompt = "Do you want to continue (y/n)? [n] ",
            defaultYes = false,
            abortMessage = s"Aborting release due to snapshot dependencies$context."
          )
    }
  }

  def aggregatedTaskValues[T](
      result: Result[Seq[KeyValue[Seq[T]]]]
  ): Either[Incomplete, Seq[T]] =
    try {
      Right(EvaluateTask.onResult(result)(_.flatMap(_.value)))
    } catch {
      case inc: Incomplete => Left(inc)
    }
}
