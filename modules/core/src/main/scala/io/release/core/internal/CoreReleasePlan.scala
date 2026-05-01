package io.release.core.internal

import cats.effect.IO
import io.release.runtime.ExecutionFlags
import io.release.runtime.ReleaseDecisionDefaults
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
    defaultAnswer: Option[String],
    versionSessionSettings: Seq[Setting[?]]
)

/** Typed startup plan for the core release command. */
private[release] final case class CoreReleasePlan(
    flags: ExecutionFlags,
    releaseVersionOverride: Option[String],
    nextVersionOverride: Option[String],
    decisionDefaults: ReleaseDecisionDefaults,
    commandName: String = CoreReleasePlan.DefaultCommandName
)

private[release] object CoreReleasePlan {

  val DefaultCommandName: String = "releaseIO"

  def fromFlags(
      useDefaults: Boolean,
      skipTests: Boolean,
      skipPublish: Boolean,
      interactive: Boolean,
      crossBuild: Boolean,
      releaseVersionOverride: Option[String],
      nextVersionOverride: Option[String],
      decisionDefaults: ReleaseDecisionDefaults = ReleaseDecisionDefaults.empty,
      commandName: String = DefaultCommandName
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

/** Internal runtime metadata threaded through [[io.release.ReleaseContext]].
  *
  * `pushConfigured` records whether `push-changes` is in the compiled step
  * sequence. The remote tag preflight (`tag-preflight`, `tag-release`) gates
  * on this so a release with `releaseIOPolicyEnablePush := false` does not
  * abort on a remote-only tag that the local push will never observe.
  *
  * Defaults to `true` to keep the conservative "push is happening" behavior
  * for legacy test paths that construct an execution state without observing
  * the compiled steps.
  */
private[release] final case class CoreExecutionState(
    plan: CoreReleasePlan,
    pushConfigured: Boolean = true
)

private[release] object CoreExecutionState {

  val key: AttributeKey[CoreExecutionState] =
    AttributeKey[CoreExecutionState]("releaseIOInternalCoreExecutionState")
}

/** Resolved release tag name, set by `tag-release` and consumed by `push-changes`.
  * Threaded through the context's metadata bag so the value survives intervening
  * `appendWithSession` calls in later steps (sbt's session-override ordering does not
  * reliably preserve setting-key assignments across subsequent append calls).
  */
private[release] object CoreReleaseTag {

  val key: AttributeKey[String] =
    AttributeKey[String]("releaseIOInternalCoreReleaseTag")
}
