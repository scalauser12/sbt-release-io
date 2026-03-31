# Configuration (monorepo)

Use this page when you need the grouped settings surface for the monorepo plugin. For an
onboarding tutorial, start with [Getting started](getting-started.md). For CLI syntax and
examples, see [Usage](usage.md).

Settings prefixed `releaseIO` come from the core plugin. Settings prefixed
`releaseIOMonorepo` are the monorepo-specific layer.

`releaseIOMonorepo` also consumes a small set of shared core `releaseIO*` settings for
decision defaults during tag-conflict handling and confirmation prompts.

## Migration note

The supported build-facing customization surface is now hook- and policy-based. Use
`releaseIOMonorepoEnable*`, `releaseIOMonorepo*Hooks`, and resource-aware custom plugins
built around `monorepoResourceHooks` instead of legacy step-list editing.

## Main settings

| Setting | Type | Default | Description |
| ------- | ---- | ------- | ----------- |
| `releaseIOMonorepoProjects` | `Seq[ProjectRef]` | all transitively aggregated subprojects | Which subprojects participate in releases |
| `releaseIOMonorepoCrossBuild` | `Boolean` | `false` | Enable cross-building by default |
| `releaseIOMonorepoSkipTests` | `Boolean` | `false` | Skip tests |
| `releaseIOMonorepoSkipPublish` | `Boolean` | `false` | Skip publish |
| `releaseIOMonorepoInteractive` | `Boolean` | `false` | Enable prompting in `run` mode |
| `releaseIOVcsRemoteCheckTimeout` | `FiniteDuration` | `60.seconds` | Timeout for the shared remote reachability check before push |
| `releaseIOMonorepoPublishArtifactsChecks` | `Boolean` | `true` | Validate `publishTo` / `publish / skip` before publish |
| `releaseIOMonorepoCommitMessage` | `String => String` | summary formatter | Commit message for release-version commits |
| `releaseIOMonorepoNextCommitMessage` | `String => String` | summary formatter | Commit message for next-version commits |

## Shared decision-default settings

These shared core settings also apply to `releaseIOMonorepo`.

| Setting | Type | Default | Description |
| ------- | ---- | ------- | ----------- |
| `releaseIODefaultTagExistsAnswer` | `Option[String]` | `None` | Default answer for per-project tag-conflict handling |
| `releaseIODefaultSnapshotDependenciesAnswer` | `Option[Boolean]` | `None` | Default answer for snapshot-dependency confirmation |
| `releaseIODefaultRemoteCheckFailureAnswer` | `Option[Boolean]` | `None` | Default answer when the shared remote check fails before push |
| `releaseIODefaultUpstreamBehindAnswer` | `Option[Boolean]` | `None` | Default answer when the local branch is behind upstream |
| `releaseIODefaultPushAnswer` | `Option[Boolean]` | `None` | Default answer for the final push confirmation |

### Example configuration

Use these shared settings when you want `build.sbt` to pre-answer the built-in monorepo
confirmation and tag-conflict decisions. They do not affect project selection or version
override syntax.

```scala
releaseIODefaultTagExistsAnswer := Some("a")
releaseIODefaultSnapshotDependenciesAnswer := Some(false)
releaseIODefaultRemoteCheckFailureAnswer := Some(false)
releaseIODefaultUpstreamBehindAnswer := Some(false)
releaseIODefaultPushAnswer := Some(true)
```

## Hook and policy settings

These settings compile into the built-in monorepo lifecycle for both `releaseIOMonorepo`
and `releaseIOMonorepo check`.

`releaseIOMonorepoSkipPublish` skips publish at runtime even if the phase still exists.
`releaseIOMonorepoEnablePublish` removes the publish phase from the compiled lifecycle
entirely.

| Setting | Type | Default | Description |
| ------- | ---- | ------- | ----------- |
| `releaseIOMonorepoEnableSnapshotDependenciesCheck` | `Boolean` | `true` | Include `check-snapshot-dependencies` |
| `releaseIOMonorepoEnableRunClean` | `Boolean` | `true` | Include `run-clean` |
| `releaseIOMonorepoEnableRunTests` | `Boolean` | `true` | Include `run-tests` |
| `releaseIOMonorepoEnableTagging` | `Boolean` | `true` | Include `tag-releases` |
| `releaseIOMonorepoEnablePublish` | `Boolean` | `true` | Include `publish-artifacts` |
| `releaseIOMonorepoEnablePush` | `Boolean` | `true` | Include `push-changes` |
| `releaseIOMonorepoBeforeSelectionHooks` | `Seq[MonorepoGlobalHookIO]` | `Seq.empty` | Hooks before `detect-or-select-projects` |
| `releaseIOMonorepoAfterSelectionHooks` | `Seq[MonorepoGlobalHookIO]` | `Seq.empty` | Hooks after `detect-or-select-projects` |
| `releaseIOMonorepoBeforeVersionResolutionHooks` | `Seq[MonorepoProjectHookIO]` | `Seq.empty` | Hooks before `inquire-versions` |
| `releaseIOMonorepoAfterVersionResolutionHooks` | `Seq[MonorepoProjectHookIO]` | `Seq.empty` | Hooks after `inquire-versions` |
| `releaseIOMonorepoBeforeReleaseVersionWriteHooks` | `Seq[MonorepoProjectHookIO]` | `Seq.empty` | Hooks before `set-release-version` |
| `releaseIOMonorepoAfterReleaseVersionWriteHooks` | `Seq[MonorepoProjectHookIO]` | `Seq.empty` | Hooks after `set-release-version` |
| `releaseIOMonorepoBeforeReleaseCommitHooks` | `Seq[MonorepoGlobalHookIO]` | `Seq.empty` | Hooks before `commit-release-versions` |
| `releaseIOMonorepoAfterReleaseCommitHooks` | `Seq[MonorepoGlobalHookIO]` | `Seq.empty` | Hooks after `commit-release-versions` |
| `releaseIOMonorepoBeforeTagHooks` | `Seq[MonorepoProjectHookIO]` | `Seq.empty` | Hooks before `tag-releases` |
| `releaseIOMonorepoAfterTagHooks` | `Seq[MonorepoProjectHookIO]` | `Seq.empty` | Hooks after `tag-releases` |
| `releaseIOMonorepoBeforePublishHooks` | `Seq[MonorepoProjectHookIO]` | `Seq.empty` | Hooks before `publish-artifacts` |
| `releaseIOMonorepoAfterPublishHooks` | `Seq[MonorepoProjectHookIO]` | `Seq.empty` | Hooks after `publish-artifacts` |
| `releaseIOMonorepoBeforeNextVersionWriteHooks` | `Seq[MonorepoProjectHookIO]` | `Seq.empty` | Hooks before `set-next-version` |
| `releaseIOMonorepoAfterNextVersionWriteHooks` | `Seq[MonorepoProjectHookIO]` | `Seq.empty` | Hooks after `set-next-version` |
| `releaseIOMonorepoBeforeNextCommitHooks` | `Seq[MonorepoGlobalHookIO]` | `Seq.empty` | Hooks before `commit-next-versions` |
| `releaseIOMonorepoAfterNextCommitHooks` | `Seq[MonorepoGlobalHookIO]` | `Seq.empty` | Hooks after `commit-next-versions` |
| `releaseIOMonorepoBeforePushHooks` | `Seq[MonorepoGlobalHookIO]` | `Seq.empty` | Hooks before `push-changes` |
| `releaseIOMonorepoAfterPushHooks` | `Seq[MonorepoGlobalHookIO]` | `Seq.empty` | Hooks after `push-changes` |

## Version settings

| Setting | Type | Default | Description |
| ------- | ---- | ------- | ----------- |
| `releaseIOMonorepoVersionFile` | `MonorepoVersionFileResolver` | scoped `releaseIOVersionFile` | Resolve each project's version file |
| `releaseIOMonorepoReadVersion` | `File => IO[String]` | regex parser | Read a version from a project's version file |
| `releaseIOMonorepoVersionFileContents` | `(File, String) => IO[String]` | `version := "x.y.z"\n` | Produce version-file contents for a project |

## Tagging settings

| Setting | Type | Default | Description |
| ------- | ---- | ------- | ----------- |
| `releaseIOMonorepoTagName` | `(String, String) => String` | `(name, ver) => s"$name/v$ver"` | Format per-project tags |
| `releaseIOMonorepoTagComment` | `(String, String) => String` | `(name, ver) => s"Release $name $ver"` | Format per-project tag comments |

## Change detection settings

| Setting | Type | Default | Description |
| ------- | ---- | ------- | ----------- |
| `releaseIOMonorepoDetectChanges` | `Boolean` | `true` | Enable git-based change detection |
| `releaseIOMonorepoIncludeDownstream` | `Boolean` | `false` | Include downstream dependents of changed projects |
| `releaseIOMonorepoChangeDetector` | `Option[(ProjectRef, File, State) => IO[Boolean]]` | `None` | Custom change detector |
| `releaseIOMonorepoDetectChangesExcludes` | `Seq[File]` | `Seq.empty` | Files to exclude from detection |
| `releaseIOMonorepoSharedPaths` | `Seq[String]` | `Seq("build.sbt", "project/")` | Root-level shared paths checked per project |

Files matching `releaseIOMonorepoSharedPaths` are checked against each project's last
release tag. If any shared file changed since that tag, the project is marked as changed.

## Example configuration

```scala
releaseIOMonorepoSkipTests := true
releaseIOMonorepoCrossBuild := true
releaseIOVcsRemoteCheckTimeout := scala.concurrent.duration.DurationInt(30).seconds
releaseIOMonorepoTagName := ((name, ver) => s"release/$name/$ver")
```
