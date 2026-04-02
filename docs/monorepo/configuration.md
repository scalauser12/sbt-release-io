# Configuration (monorepo)

Use this page when you need the grouped settings surface for the monorepo plugin. For an
onboarding tutorial, start with [Getting started](getting-started.md). For CLI syntax and
examples, see [Usage](usage.md).

Settings prefixed `releaseIO` come from the core plugin. Settings prefixed
`releaseIOMonorepo` are the monorepo-specific layer.

`releaseIOMonorepo` also consumes shared core `releaseIODefaults*` and
`releaseIOVcsRemoteCheckTimeout` settings for decision defaults and the pre-push remote check.

## Migration note

The supported build-facing customization surface is now hook- and policy-based. Use
grouped `releaseIOMonorepoPolicy*`, `releaseIOMonorepoHooks*`, and resource-aware custom plugins
built around `monorepoResourceHooks` instead of legacy step-list editing.

Use the grouped names in `build.sbt`. The older flat names remain as deprecated aliases in this
release. `inspect`, `show`, and key index output still display the legacy sbt key labels because
the underlying key instances were not renamed yet.

## Grouped key migration

| Old name | Preferred grouped name |
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
| `releaseIOMonorepoBeforePublishHooks` | `releaseIOMonorepoHooksBeforePublish` |

## Selection settings

| Setting | Type | Default | Description |
| ------- | ---- | ------- | ----------- |
| `releaseIOMonorepoSelectionProjects` | `Seq[ProjectRef]` | all transitively aggregated subprojects | Which subprojects participate in releases |

## Behavior settings

| Setting | Type | Default | Description |
| ------- | ---- | ------- | ----------- |
| `releaseIOMonorepoBehaviorCrossBuild` | `Boolean` | `false` | Enable cross-building by default |
| `releaseIOMonorepoBehaviorSkipTests` | `Boolean` | `false` | Skip tests |
| `releaseIOMonorepoBehaviorSkipPublish` | `Boolean` | `false` | Skip publish |
| `releaseIOMonorepoBehaviorInteractive` | `Boolean` | `false` | Enable prompting in `run` mode |

## Shared core settings

| Setting | Type | Default | Description |
| ------- | ---- | ------- | ----------- |
| `releaseIOVcsRemoteCheckTimeout` | `FiniteDuration` | `60.seconds` | Timeout for the shared remote reachability check before push |

## Shared decision-default settings

These shared core settings also apply to `releaseIOMonorepo`.

| Setting | Type | Default | Description |
| ------- | ---- | ------- | ----------- |
| `releaseIODefaultsTagExistsAnswer` | `Option[String]` | `None` | Default answer for per-project tag-conflict handling |
| `releaseIODefaultsSnapshotDependenciesAnswer` | `Option[Boolean]` | `None` | Default answer for snapshot-dependency confirmation |
| `releaseIODefaultsRemoteCheckFailureAnswer` | `Option[Boolean]` | `None` | Default answer when the shared remote check fails before push |
| `releaseIODefaultsUpstreamBehindAnswer` | `Option[Boolean]` | `None` | Default answer when the local branch is behind upstream |
| `releaseIODefaultsPushAnswer` | `Option[Boolean]` | `None` | Default answer for the final push confirmation |

### Example configuration

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

## Hook and policy settings

These settings compile into the built-in monorepo lifecycle for both `releaseIOMonorepo`
and `releaseIOMonorepo check`.

`releaseIOMonorepoBehaviorSkipPublish` skips publish at runtime even if the phase still exists.
`releaseIOMonorepoPolicyEnablePublish` removes the publish phase from the compiled lifecycle
entirely, so `releaseIOMonorepoHooksBeforePublish` / `releaseIOMonorepoHooksAfterPublish` do not
exist when it is `false`.

| Setting | Type | Default | Description |
| ------- | ---- | ------- | ----------- |
| `releaseIOMonorepoPolicyEnableSnapshotDependenciesCheck` | `Boolean` | `true` | Include `check-snapshot-dependencies` |
| `releaseIOMonorepoPolicyEnableRunClean` | `Boolean` | `true` | Include `run-clean` |
| `releaseIOMonorepoPolicyEnableRunTests` | `Boolean` | `true` | Include `run-tests` |
| `releaseIOMonorepoPolicyEnableTagging` | `Boolean` | `true` | Include `tag-releases` |
| `releaseIOMonorepoPolicyEnablePublish` | `Boolean` | `true` | Include `publish-artifacts` |
| `releaseIOMonorepoPolicyEnablePush` | `Boolean` | `true` | Include `push-changes` |
| `releaseIOMonorepoHooksBeforeSelection` | `Seq[MonorepoGlobalHookIO]` | `Seq.empty` | Hooks before `detect-or-select-projects` |
| `releaseIOMonorepoHooksAfterSelection` | `Seq[MonorepoGlobalHookIO]` | `Seq.empty` | Hooks after `detect-or-select-projects` |
| `releaseIOMonorepoHooksBeforeVersionResolution` | `Seq[MonorepoProjectHookIO]` | `Seq.empty` | Hooks before `inquire-versions` |
| `releaseIOMonorepoHooksAfterVersionResolution` | `Seq[MonorepoProjectHookIO]` | `Seq.empty` | Hooks after `inquire-versions` |
| `releaseIOMonorepoHooksBeforeReleaseVersionWrite` | `Seq[MonorepoProjectHookIO]` | `Seq.empty` | Hooks before `set-release-version` |
| `releaseIOMonorepoHooksAfterReleaseVersionWrite` | `Seq[MonorepoProjectHookIO]` | `Seq.empty` | Hooks after `set-release-version` |
| `releaseIOMonorepoHooksBeforeReleaseCommit` | `Seq[MonorepoGlobalHookIO]` | `Seq.empty` | Hooks before `commit-release-versions` |
| `releaseIOMonorepoHooksAfterReleaseCommit` | `Seq[MonorepoGlobalHookIO]` | `Seq.empty` | Hooks after `commit-release-versions` |
| `releaseIOMonorepoHooksBeforeTag` | `Seq[MonorepoProjectHookIO]` | `Seq.empty` | Hooks before `tag-releases` |
| `releaseIOMonorepoHooksAfterTag` | `Seq[MonorepoProjectHookIO]` | `Seq.empty` | Hooks after `tag-releases` |
| `releaseIOMonorepoHooksBeforePublish` | `Seq[MonorepoProjectHookIO]` | `Seq.empty` | Hooks before `publish-artifacts` |
| `releaseIOMonorepoHooksAfterPublish` | `Seq[MonorepoProjectHookIO]` | `Seq.empty` | Hooks after `publish-artifacts` |
| `releaseIOMonorepoHooksBeforeNextVersionWrite` | `Seq[MonorepoProjectHookIO]` | `Seq.empty` | Hooks before `set-next-version` |
| `releaseIOMonorepoHooksAfterNextVersionWrite` | `Seq[MonorepoProjectHookIO]` | `Seq.empty` | Hooks after `set-next-version` |
| `releaseIOMonorepoHooksBeforeNextCommit` | `Seq[MonorepoGlobalHookIO]` | `Seq.empty` | Hooks before `commit-next-versions` |
| `releaseIOMonorepoHooksAfterNextCommit` | `Seq[MonorepoGlobalHookIO]` | `Seq.empty` | Hooks after `commit-next-versions` |
| `releaseIOMonorepoHooksBeforePush` | `Seq[MonorepoGlobalHookIO]` | `Seq.empty` | Hooks before `push-changes` |
| `releaseIOMonorepoHooksAfterPush` | `Seq[MonorepoGlobalHookIO]` | `Seq.empty` | Hooks after `push-changes` |

## Versioning settings

| Setting | Type | Default | Description |
| ------- | ---- | ------- | ----------- |
| `releaseIOMonorepoVersioningFile` | `MonorepoVersionFileResolver` | scoped `releaseIOVersioningFile` | Resolve each project's version file |
| `releaseIOMonorepoVersioningReadVersion` | `File => IO[String]` | regex parser | Read a version from a project's version file |
| `releaseIOMonorepoVersioningFileContents` | `(File, String) => IO[String]` | `version := "x.y.z"\n` | Produce version-file contents for a project |

## VCS settings

| Setting | Type | Default | Description |
| ------- | ---- | ------- | ----------- |
| `releaseIOMonorepoVcsTagName` | `(String, String) => String` | `(name, ver) => s"$name/v$ver"` | Format per-project tags |
| `releaseIOMonorepoVcsTagComment` | `(String, String) => String` | `(name, ver) => s"Release $name $ver"` | Format per-project tag comments |
| `releaseIOMonorepoVcsReleaseCommitMessage` | `String => String` | summary formatter | Commit message for release-version commits |
| `releaseIOMonorepoVcsNextCommitMessage` | `String => String` | summary formatter | Commit message for next-version commits |

## Publish settings

| Setting | Type | Default | Description |
| ------- | ---- | ------- | ----------- |
| `releaseIOMonorepoPublishChecks` | `Boolean` | `true` | Validate `publishTo` / `publish / skip` before publish |

## Change detection settings

| Setting | Type | Default | Description |
| ------- | ---- | ------- | ----------- |
| `releaseIOMonorepoDetectionEnabled` | `Boolean` | `true` | Enable git-based change detection |
| `releaseIOMonorepoDetectionIncludeDownstream` | `Boolean` | `false` | Include downstream dependents of changed projects |
| `releaseIOMonorepoDetectionChangeDetector` | `Option[(ProjectRef, File, State) => IO[Boolean]]` | `None` | Custom change detector |
| `releaseIOMonorepoDetectionExcludes` | `Seq[File]` | `Seq.empty` | Files to exclude from detection |
| `releaseIOMonorepoDetectionSharedPaths` | `Seq[String]` | `Seq("build.sbt", "project/")` | Root-level shared paths checked per project |

Files matching `releaseIOMonorepoDetectionSharedPaths` are checked against each project's last
release tag. If any shared file changed since that tag, the project is marked as changed.

## Example configuration

```scala
releaseIOMonorepoBehaviorSkipTests := true
releaseIOMonorepoBehaviorCrossBuild := true
releaseIOVcsRemoteCheckTimeout := scala.concurrent.duration.DurationInt(30).seconds
releaseIOMonorepoVcsTagName := ((name, ver) => s"release/$name/$ver")
```
