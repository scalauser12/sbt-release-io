# Configuration (monorepo)

Use this page for starter `build.sbt` patterns and common configuration recipes. For the
exhaustive settings catalog, see [Settings reference](reference.md). For an onboarding
tutorial, start with [Getting started](getting-started.md). For CLI syntax and examples,
see [Usage](usage.md).

Settings prefixed `releaseIOMonorepo` are the monorepo-specific layer.
Shared/core settings prefixed `releaseIO` are owned by the core plugin surface and are available
transitively because `MonorepoReleasePlugin` requires `ReleasePluginIO`.

`releaseIOMonorepo` also consumes shared `releaseIODefaults*` and
`releaseIOVcsRemoteCheckTimeout` settings for decision defaults and the pre-push remote check.

## Example: Persistent decision defaults

Use these shared settings when you want `build.sbt` to pre-answer the built-in monorepo
confirmation and tag-conflict decisions. They do not affect project selection or version
override syntax.

```scala
releaseIODefaultsTagExistsAnswer := Some("a")
releaseIODefaultsSnapshotDependenciesAnswer := Some(false)
releaseIODefaultsRemoteCheckFailureAnswer := Some(false)
releaseIODefaultsUpstreamBehindAnswer := Some(false)
releaseIODefaultsPushAnswer := Some(true)
```

## Example: Starter configuration

```scala
releaseIOMonorepoBehaviorSkipTests := true
releaseIOMonorepoBehaviorCrossBuild := true
releaseIOVcsRemoteCheckTimeout := scala.concurrent.duration.DurationInt(30).seconds
releaseIOMonorepoVcsTagName := ((name, ver) => s"release/$name/$ver")
```
