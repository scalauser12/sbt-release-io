# Configuration (monorepo)

Use this page for starter `build.sbt` patterns and common configuration recipes. For the
exhaustive settings catalog, see [Settings reference](reference.md). For an onboarding
tutorial, start with [Getting started](getting-started.md). For CLI syntax and examples,
see [Usage](usage.md).

Settings prefixed `releaseIO` come from the core plugin. Settings prefixed
`releaseIOMonorepo` are the monorepo-specific layer.

`releaseIOMonorepo` also consumes shared core `releaseIODefaults*` and
`releaseIOVcsRemoteCheckTimeout` settings for decision defaults and the pre-push remote check.

## Migration note

The supported build-facing customization surface is now hook- and policy-based. Use
grouped `releaseIOMonorepoPolicy*`, `releaseIOMonorepoHooks*`, and resource-aware custom plugins
built around `monorepoResourceHooks` instead of legacy step-list editing.

Use the grouped names in `build.sbt`. The older flat names were removed in the breaking cleanup,
and the grouped names are now the canonical sbt key labels.

## Grouped key migration

| Removed name | Replacement |
| -------- | ---------------------- |
| `releaseIOMonorepoProjects` | `releaseIOMonorepoSelectionProjects` |
| `releaseIOMonorepoCrossBuild` | `releaseIOMonorepoBehaviorCrossBuild` |
| `releaseIOMonorepoSkipTests` | `releaseIOMonorepoBehaviorSkipTests` |
| `releaseIOMonorepoSkipPublish` | `releaseIOMonorepoBehaviorSkipPublish` |
| `releaseIOMonorepoCommitMessage` | `releaseIOMonorepoVcsReleaseCommitMessage` |
| `releaseIOMonorepoTagName` | `releaseIOMonorepoVcsTagName` |
| `releaseIOMonorepoDetectChanges` | `releaseIOMonorepoDetectionEnabled` |
| `releaseIOMonorepoIncludeDownstream` | `releaseIOMonorepoDetectionIncludeDownstream` |
| `releaseIOMonorepoEnablePush` | `releaseIOMonorepoPolicyEnablePush` |
| `releaseIOMonorepoAfterCleanCheckHooks` | `releaseIOMonorepoHooksAfterCleanCheck` |
| `releaseIOMonorepoBeforePublishHooks` | `releaseIOMonorepoHooksBeforePublish` |

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
