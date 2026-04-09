package io.release.runtime.engine

/** Typed built-in step roles used for orchestration-only decisions. */
private[release] sealed trait BuiltInStepRole

private[release] object BuiltInStepRole {
  case object InitializeVcs extends BuiltInStepRole
  case object ProjectSelection extends BuiltInStepRole
  case object ResolveVersions extends BuiltInStepRole
  case object TagRelease extends BuiltInStepRole
  case object PublishArtifacts extends BuiltInStepRole
  case object PushChanges extends BuiltInStepRole
}
