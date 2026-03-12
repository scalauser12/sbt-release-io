package io.release.monorepo.internal

import cats.effect.IO
import io.release.internal.ExecutionFlags
import io.release.monorepo.ProjectReleaseInfo
import sbt.State

/** Builds and stores the stable startup plan for the monorepo release command. */
private[monorepo] object MonorepoReleasePlanner {

  final case class Inputs(
      flags: ExecutionFlags,
      allChanged: Boolean,
      selectedNames: Seq[String],
      releaseVersionPairs: Seq[(String, String)],
      nextVersionPairs: Seq[(String, String)],
      globalReleaseVersions: Seq[String],
      globalNextVersions: Seq[String]
  )

  final case class ValidatedInputs(
      flags: ExecutionFlags,
      allChanged: Boolean,
      selectedNames: Seq[String],
      releaseVersionOverrides: Map[String, String],
      nextVersionOverrides: Map[String, String],
      globalReleaseVersion: Option[String],
      globalNextVersion: Option[String]
  ) {
    def selectionMode: SelectionMode =
      if (selectedNames.nonEmpty) SelectionMode.ExplicitSelection
      else if (allChanged) SelectionMode.AllChanged
      else SelectionMode.DetectChanges
  }

  def build(state: State, inputs: Inputs): IO[Either[State, MonorepoReleasePlan]] =
    validateOverrideInputs(inputs) match {
      case Left(message)    =>
        IO.blocking {
          state.log.error(s"[release-io-monorepo] $message")
          Left(state.fail)
        }
      case Right(validated) =>
        IO.pure(
          Right(
            MonorepoReleasePlan(
              flags = validated.flags,
              selectionMode = validated.selectionMode,
              selectedNames = validated.selectedNames,
              releaseVersionOverrides = validated.releaseVersionOverrides,
              nextVersionOverrides = validated.nextVersionOverrides,
              globalReleaseVersion = validated.globalReleaseVersion,
              globalNextVersion = validated.globalNextVersion
            )
          )
        )
    }

  def validateOverrideInputs(inputs: Inputs): Either[String, ValidatedInputs] = {
    val releaseVersionPairs     = inputs.releaseVersionPairs
    val nextVersionPairs        = inputs.nextVersionPairs
    val releaseVersionOverrides = releaseVersionPairs.toMap
    val nextVersionOverrides    = nextVersionPairs.toMap
    val globalReleaseVersions   = inputs.globalReleaseVersions
    val globalNextVersions      = inputs.globalNextVersions
    val globalReleaseVersion    = globalReleaseVersions.headOption
    val globalNextVersion       = globalNextVersions.headOption

    def failWhen(condition: Boolean, msg: => String): Either[String, Unit] =
      if (condition) Left(msg) else Right(())

    for {
      _ <- failWhen(
             releaseVersionPairs.exists { case (p, v) => p.isEmpty || v.isEmpty },
             "Invalid release-version format. Expected project=version"
           )
      _ <- failWhen(
             nextVersionPairs.exists { case (p, v) => p.isEmpty || v.isEmpty },
             "Invalid next-version format. Expected project=version"
           )
      _ <- failWhen(
             globalReleaseVersion.exists(_.isEmpty),
             "Invalid release-version format. Expected a non-empty version string"
           )
      _ <- failWhen(
             globalNextVersion.exists(_.isEmpty),
             "Invalid next-version format. Expected a non-empty version string"
           )
      _ <- failWhen(
             releaseVersionPairs.groupBy(_._1).exists(_._2.length > 1),
             "Duplicate per-project release-version overrides: " +
               releaseVersionPairs.groupBy(_._1).filter(_._2.length > 1).keys.mkString(", ")
           )
      _ <- failWhen(
             nextVersionPairs.groupBy(_._1).exists(_._2.length > 1),
             "Duplicate per-project next-version overrides: " +
               nextVersionPairs.groupBy(_._1).filter(_._2.length > 1).keys.mkString(", ")
           )
      _ <- failWhen(
             globalReleaseVersions.length > 1,
             "Multiple global release-version overrides provided. Only one is allowed"
           )
      _ <- failWhen(
             globalNextVersions.length > 1,
             "Multiple global next-version overrides provided. Only one is allowed"
           )
      _ <- failWhen(
             (globalReleaseVersion.nonEmpty || globalNextVersion.nonEmpty) &&
               (releaseVersionOverrides.nonEmpty || nextVersionOverrides.nonEmpty),
             "Cannot mix global version overrides with per-project version overrides"
           )
      _ <- failWhen(
             inputs.allChanged && inputs.selectedNames.nonEmpty,
             "Cannot combine 'all-changed' with explicit project selection. " +
               "Either use 'all-changed' alone or specify projects explicitly."
           )
    } yield ValidatedInputs(
      flags = inputs.flags,
      allChanged = inputs.allChanged,
      selectedNames = inputs.selectedNames,
      releaseVersionOverrides = releaseVersionOverrides,
      nextVersionOverrides = nextVersionOverrides,
      globalReleaseVersion = globalReleaseVersion,
      globalNextVersion = globalNextVersion
    )
  }

  def enforceGlobalVersionAllOrNothing(
      allProjects: Seq[ProjectReleaseInfo],
      changedProjects: Seq[ProjectReleaseInfo],
      useGlobalVersion: Boolean
  ): IO[Seq[ProjectReleaseInfo]] = {
    val changedRefs = changedProjects.map(_.ref).toSet
    val allRefs     = allProjects.map(_.ref).toSet
    if (!useGlobalVersion || changedProjects.isEmpty || changedRefs == allRefs)
      IO.pure(changedProjects)
    else {
      val changedNames  = changedProjects.map(_.name).mkString(", ")
      val excludedNames =
        allProjects.filterNot(p => changedRefs.contains(p.ref)).map(_.name).mkString(", ")
      IO.raiseError(
        new IllegalStateException(
          "Global version mode is active, but change detection selected only a subset of projects. " +
            s"Changed: $changedNames. Excluded: $excludedNames. " +
            "Release all projects (for example, use `all-changed`), disable change detection, " +
            "or disable releaseIOMonorepoUseGlobalVersion."
        )
      )
    }
  }
}
