# Configuration (monorepo)

Settings prefixed `releaseIO` (no `Monorepo`) come from the **core plugin** (`sbt-release-io`) and are
available whenever that plugin is on the classpath. Settings prefixed `releaseIOMonorepo` come from
**this plugin**. Several monorepo settings mirror their core counterpart with a different default — for
example `releaseIOMonorepoVersionFile` resolves per-project files, while `releaseIOVersionFile` is a
single root-project file. Always configure the `releaseIOMonorepo*` variant when using the monorepo plugin.

## Core settings

| Setting | Type | Default | Description |
|-----|------|---------|-------------|
| `releaseIOMonorepoProjects` | `Seq[ProjectRef]` | All transitively aggregated subprojects | Which subprojects participate in releases |
| `releaseIOMonorepoProcess` | `Seq[MonorepoStepIO]` | `MonorepoReleaseSteps.defaults` | Ordered release steps |
| `releaseIOMonorepoCrossBuild` | `Boolean` | `false` | Enable cross-building by default |
| `releaseIOMonorepoSkipTests` | `Boolean` | `false` | Skip tests |
| `releaseIOMonorepoSkipPublish` | `Boolean` | `false` | Skip publish |
| `releaseIOMonorepoInteractive` | `Boolean` | `false` | When true, `inquire-versions` prompts interactively. `with-defaults` overrides to false; CLI version overrides bypass prompts for those projects. |
| `releaseIOMonorepoPublishArtifactsChecks` | `Boolean` | `true` | When false, skips the check that each project has `publishTo` configured or `publish / skip := true` |
| `releaseIOMonorepoCommitMessage` | `String => String` | `summary => s"Setting release versions: $summary"` | Commit message formatter for release version commits. Receives the version summary (e.g. "core 1.0.0, api 2.0.0"). |
| `releaseIOMonorepoNextCommitMessage` | `String => String` | `summary => s"Setting next versions: $summary"` | Commit message formatter for next version commits. Receives the version summary. |

## Hook / policy settings

These settings participate in the compiled hook-based flow when the raw process is left
alone. If `releaseIOMonorepoProcess`, `monorepoReleaseProcess`, or
`monorepoReleaseCheckProcess` are customized, the plugin stays in legacy raw-process
mode and ignores these keys.

| Setting | Type | Default | Description |
|-----|------|---------|-------------|
| `releaseIOMonorepoEnableSnapshotDependenciesCheck` | `Boolean` | `true` | Include `check-snapshot-dependencies` in the compiled process |
| `releaseIOMonorepoEnableRunTests` | `Boolean` | `true` | Include `run-tests` in the compiled process |
| `releaseIOMonorepoEnableTagging` | `Boolean` | `true` | Include `tag-releases` in the compiled process |
| `releaseIOMonorepoEnablePublish` | `Boolean` | `true` | Include `publish-artifacts` in the compiled process |
| `releaseIOMonorepoEnablePush` | `Boolean` | `true` | Include `push-changes` in the compiled process |
| `releaseIOMonorepoBeforeSelectionHooks` | `Seq[MonorepoGlobalHookIO]` | `Seq.empty` | Hooks that run before `detect-or-select-projects` |
| `releaseIOMonorepoAfterSelectionHooks` | `Seq[MonorepoGlobalHookIO]` | `Seq.empty` | Hooks that run after `detect-or-select-projects` |
| `releaseIOMonorepoBeforeVersionResolutionHooks` | `Seq[MonorepoProjectHookIO]` | `Seq.empty` | Hooks that run before `inquire-versions` |
| `releaseIOMonorepoAfterVersionResolutionHooks` | `Seq[MonorepoProjectHookIO]` | `Seq.empty` | Hooks that run after `inquire-versions` |
| `releaseIOMonorepoBeforeReleaseVersionWriteHooks` | `Seq[MonorepoProjectHookIO]` | `Seq.empty` | Hooks that run before `set-release-version` |
| `releaseIOMonorepoAfterReleaseVersionWriteHooks` | `Seq[MonorepoProjectHookIO]` | `Seq.empty` | Hooks that run after `set-release-version` |
| `releaseIOMonorepoBeforeReleaseCommitHooks` | `Seq[MonorepoGlobalHookIO]` | `Seq.empty` | Hooks that run before `commit-release-versions` |
| `releaseIOMonorepoAfterReleaseCommitHooks` | `Seq[MonorepoGlobalHookIO]` | `Seq.empty` | Hooks that run after `commit-release-versions` |
| `releaseIOMonorepoBeforeTagHooks` | `Seq[MonorepoProjectHookIO]` | `Seq.empty` | Hooks that run before `tag-releases` when tagging is enabled |
| `releaseIOMonorepoAfterTagHooks` | `Seq[MonorepoProjectHookIO]` | `Seq.empty` | Hooks that run after `tag-releases` when tagging is enabled |
| `releaseIOMonorepoBeforePublishHooks` | `Seq[MonorepoProjectHookIO]` | `Seq.empty` | Hooks that run before `publish-artifacts` when publish runs |
| `releaseIOMonorepoAfterPublishHooks` | `Seq[MonorepoProjectHookIO]` | `Seq.empty` | Hooks that run after `publish-artifacts` when publish runs |
| `releaseIOMonorepoBeforeNextVersionWriteHooks` | `Seq[MonorepoProjectHookIO]` | `Seq.empty` | Hooks that run before `set-next-version` |
| `releaseIOMonorepoAfterNextVersionWriteHooks` | `Seq[MonorepoProjectHookIO]` | `Seq.empty` | Hooks that run after `set-next-version` |
| `releaseIOMonorepoBeforeNextCommitHooks` | `Seq[MonorepoGlobalHookIO]` | `Seq.empty` | Hooks that run before `commit-next-versions` |
| `releaseIOMonorepoAfterNextCommitHooks` | `Seq[MonorepoGlobalHookIO]` | `Seq.empty` | Hooks that run after `commit-next-versions` |
| `releaseIOMonorepoBeforePushHooks` | `Seq[MonorepoGlobalHookIO]` | `Seq.empty` | Hooks that run before `push-changes` when push is enabled |
| `releaseIOMonorepoAfterPushHooks` | `Seq[MonorepoGlobalHookIO]` | `Seq.empty` | Hooks that run after `push-changes` when push is enabled |

## Version settings

| Setting | Type | Default | Description |
|-----|------|---------|-------------|
| `releaseIOMonorepoVersionFile` | `MonorepoVersionFileResolver` | Scoped `releaseIOVersionFile` | Per-project version file resolver `(ProjectRef, State) => File`. Called during version inquiry and write steps. Default reads each project's scoped `releaseIOVersionFile` (typically `<projectDir>/version.sbt`). |
| `releaseIOMonorepoReadVersion` | `File => IO[String]` | Regex parser (same as core) | Version file reader |
| `releaseIOMonorepoVersionFileContents` | `(File, String) => IO[String]` | `version := "x.y.z"\n` | Returns the new version file content as a string. The plugin writes this string to disk. The `File` arg is the current version file, available for reading existing content (e.g., to preserve other fields during partial updates); the default ignores it. |

## Tagging settings

| Setting | Type | Default | Description |
|-----|------|---------|-------------|
| `releaseIOMonorepoTagName` | `(String, String) => String` | `(name, ver) => s"$name/v$ver"` | Per-project tag formatter. Must preserve `*` literally (used as a glob wildcard for change detection) |
| `releaseIOMonorepoTagComment` | `(String, String) => String` | `(name, ver) => s"Release $name $ver"` | Per-project tag comment formatter |

## Change detection settings

| Setting | Type | Default | Description |
|-----|------|---------|-------------|
| `releaseIOMonorepoDetectChanges` | `Boolean` | `true` | Enable git-based change detection |
| `releaseIOMonorepoIncludeDownstream` | `Boolean` | `false` | Include transitive downstream dependents of changed projects |
| `releaseIOMonorepoChangeDetector` | `Option[(ProjectRef, File, State) => IO[Boolean]]` | `None` | Custom change detector |
| `releaseIOMonorepoDetectChangesExcludes` | `Seq[File]` | `Seq.empty` | Files to exclude from detection |
| `releaseIOMonorepoSharedPaths` | `Seq[String]` | `Seq("build.sbt", "project/")` | Root-level paths checked for shared changes per project |

Files matching `releaseIOMonorepoSharedPaths` (relative to the repo root) are checked against each project's last release tag. If any shared file changed since that tag, the project is marked as changed. This catches modifications to shared build definitions, compiler plugins, or dependency versions. Set to `Seq.empty` to disable.

```scala
// Add extra shared paths (e.g. a shared source directory and formatting config)
releaseIOMonorepoSharedPaths := Seq("build.sbt", "project/", "shared/", ".scalafmt.conf")

// Or disable shared path detection entirely
releaseIOMonorepoSharedPaths := Seq.empty
```

## Example configuration

```scala
releaseIOMonorepoSkipTests  := true
releaseIOMonorepoCrossBuild := true
releaseIOMonorepoTagName    := ((name, ver) => s"release/$name/$ver")
```
