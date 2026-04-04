package io.release.internal

import cats.effect.IO
import sbt.{internal as _, *}

/** Normalized execution flags shared by core and monorepo planners. */
private[release] final case class ExecutionFlags(
    useDefaults: Boolean,
    skipTests: Boolean,
    skipPublish: Boolean,
    interactive: Boolean,
    crossBuild: Boolean
)

/** Resolved versioning inputs for the core release flow. */
private[release] final case class VersionPlan(
    versionFile: File,
    readVersion: File => IO[String],
    versionFileContents: (File, String) => IO[String],
    releaseVersionOverride: Option[String],
    nextVersionOverride: Option[String],
    useGlobalVersion: Boolean
)

/** Resolved tagging inputs for the core release flow. */
private[release] final case class TagPlan(
    state: sbt.State,
    tagName: String,
    tagComment: String,
    sign: Boolean,
    defaultAnswer: Option[String],
    versionSessionSettings: Seq[Setting[?]]
)

/** Typed startup plan for the core release command. */
private[release] final case class CoreReleasePlan(
    flags: ExecutionFlags,
    releaseVersionOverride: Option[String],
    nextVersionOverride: Option[String],
    decisionDefaults: ReleaseDecisionDefaults,
    commandName: String = "releaseIO"
)

private[release] object CoreReleasePlan {

  def fromFlags(
      useDefaults: Boolean,
      skipTests: Boolean,
      skipPublish: Boolean,
      interactive: Boolean,
      crossBuild: Boolean,
      releaseVersionOverride: Option[String],
      nextVersionOverride: Option[String],
      decisionDefaults: ReleaseDecisionDefaults = ReleaseDecisionDefaults.empty,
      commandName: String = "releaseIO"
  ): CoreReleasePlan =
    CoreReleasePlan(
      flags = ExecutionFlags(
        useDefaults = useDefaults,
        skipTests = skipTests,
        skipPublish = skipPublish,
        interactive = interactive,
        crossBuild = crossBuild
      ),
      releaseVersionOverride = releaseVersionOverride,
      nextVersionOverride = nextVersionOverride,
      decisionDefaults = decisionDefaults,
      commandName = commandName
    )
}

/** Internal runtime metadata threaded through [[io.release.ReleaseContext]]. */
private[release] final case class CoreExecutionState(plan: CoreReleasePlan)

private[release] object CoreExecutionState {

  val key: AttributeKey[CoreExecutionState] =
    AttributeKey[CoreExecutionState]("releaseIOInternalCoreExecutionState")
}
