package io.release.steps

import cats.effect.IO
import io.release.ReleaseContext
import io.release.internal.CoreStepFactory
import io.release.internal.ProcessStep
import io.release.internal.VersionPlan
import sbt.State
import sbt.{internal as _, *}

/** Version-related release steps: inquire, set, commit versions. */
private[release] object VersionSteps {

  private[steps] type ResolvedSettings = ReleaseVersionWorkflow.ResolvedSettings
  private[steps] val ResolvedSettings = ReleaseVersionWorkflow.ResolvedSettings

  private[release] type ResolvedVersions = ReleaseVersionWorkflow.ResolvedVersions
  private[release] val ResolvedVersions = ReleaseVersionWorkflow.ResolvedVersions

  private[steps] def resolveCurrentSettings(state: State): ResolvedSettings =
    ReleaseVersionWorkflow.resolveCurrentSettings(state)

  private[steps] def sessionSettings(state: State): Seq[Setting[?]] =
    ReleaseVersionWorkflow.sessionSettings(state)

  private[steps] def sessionSettings(versionPlan: VersionPlan): Seq[Setting[?]] =
    ReleaseVersionWorkflow.sessionSettings(versionPlan)

  private val versionPattern = """(?:ThisBuild\s*/\s*)?version\s*:=\s*"([^"]+)"""".r

  /** Default version file reader. Parses `[ThisBuild /] version := "x.y.z"`.
    * Skips comment lines to avoid matching commented-out versions.
    */
  val defaultReadVersion: File => IO[String] = { file =>
    for {
      contents <- IO.blocking(sbt.IO.read(file))
      result   <- IO.fromOption {
                    contents
                      .replaceAll("""(?s)/\*.*?\*/""", "")
                      .linesIterator
                      .map(_.trim)
                      .filterNot(_.startsWith("//"))
                      .flatMap(versionPattern.findFirstMatchIn(_).map(_.group(1)))
                      .buffered
                      .headOption
                  }(
                    new IllegalStateException(
                      s"Could not parse version from ${file.getName}. " +
                        s"""Expected format: [ThisBuild /] version := "x.y.z"\nContents:\n$contents\n""" +
                        "If you use a custom version file format, configure " +
                        "`releaseIOVersioningFile`, `releaseIOVersioningReadVersion`, and " +
                        "`releaseIOVersioningFileContents`. See `releaseIO help` for examples."
                    )
                  )
    } yield result
  }

  /** Default version file writer. Produces `[ThisBuild /] version := "x.y.z"`. */
  def defaultWriteVersion(useGlobalVersion: Boolean): (File, String) => IO[String] =
    (_, ver) => {
      val key = if (useGlobalVersion) "ThisBuild / version" else "version"
      IO.pure(s"""$key := "$ver"\n""")
    }

  val inquireVersions: ProcessStep.Single[ReleaseContext] = ProcessStep.Single(
    name = "inquire-versions",
    validate = ReleaseVersionWorkflow.validateInquireVersions,
    execute = ReleaseVersionWorkflow.inquireVersions
  )

  val setReleaseVersion: ProcessStep.Single[ReleaseContext] =
    CoreStepFactory.io("set-release-version")(ReleaseVersionWorkflow.writeReleaseVersion)

  val setNextVersion: ProcessStep.Single[ReleaseContext] =
    CoreStepFactory.io("set-next-version")(ReleaseVersionWorkflow.writeNextVersion)

  val commitReleaseVersion: ProcessStep.Single[ReleaseContext] = ProcessStep.Single(
    name = "commit-release-version",
    validate = VcsSteps.validateCleanWorkingDir(_, logStartHash = false),
    execute = ReleaseVersionWorkflow.commitReleaseVersion
  )

  val commitNextVersion: ProcessStep.Single[ReleaseContext] = ProcessStep.Single(
    name = "commit-next-version",
    validate = VcsSteps.validateCleanWorkingDir(_, logStartHash = false),
    execute = ReleaseVersionWorkflow.commitNextVersion
  )

  private[release] def resolveVersions(
      ctx: ReleaseContext,
      allowPrompts: Boolean
  ): IO[(ReleaseContext, ResolvedVersions)] =
    ReleaseVersionWorkflow.resolveVersions(ctx, allowPrompts)

  private[release] def resolveVersionPlan(
      ctx: ReleaseContext,
      resolveSettings: State => ResolvedSettings = resolveCurrentSettings
  ): VersionPlan =
    ReleaseVersionWorkflow.resolveVersionPlan(ctx, resolveSettings)
}
