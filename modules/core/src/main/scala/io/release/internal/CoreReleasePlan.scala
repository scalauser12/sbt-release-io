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
    defaultAnswer: Option[String]
)

/** Typed execution plan for the core release command. */
private[release] final case class CoreReleasePlan(
    flags: ExecutionFlags,
    version: VersionPlan,
    tag: TagPlan
)
