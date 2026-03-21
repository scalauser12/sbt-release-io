package io.release.internal

import cats.effect.IO
import sbt.{internal as _, *}

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
    defaultAnswer: Option[String]
)

/** Typed startup plan for the core release command. */
private[release] final case class CoreReleasePlan(
    flags: ExecutionFlags,
    releaseVersionOverride: Option[String],
    nextVersionOverride: Option[String],
    tagDefault: Option[String]
)

/** Builds the typed execution plan for the core release command. */
private[release] object CoreReleasePlan {

  final case class Inputs(
      useDefaults: Boolean,
      skipTests: Boolean,
      skipPublish: Boolean,
      interactive: Boolean,
      crossBuild: Boolean,
      releaseVersionOverride: Option[String],
      nextVersionOverride: Option[String],
      tagDefault: Option[String]
  )

  def build(inputs: Inputs): CoreReleasePlan = {
    val flags = ExecutionFlags(
      useDefaults = inputs.useDefaults,
      skipTests = inputs.skipTests,
      skipPublish = inputs.skipPublish,
      interactive = inputs.interactive,
      crossBuild = inputs.crossBuild
    )
    CoreReleasePlan(
      flags = flags,
      releaseVersionOverride = inputs.releaseVersionOverride,
      nextVersionOverride = inputs.nextVersionOverride,
      tagDefault = inputs.tagDefault
    )
  }
}
