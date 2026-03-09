package io.release.internal

import cats.effect.IO
import sbt.File

/** Resolved versioning inputs for the core release flow. */
private[release] final case class VersionPlan(
    versionFile: File,
    readVersion: File => IO[String],
    writeVersion: (File, String) => IO[String],
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
