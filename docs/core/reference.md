# Settings reference (core)

This page is the exhaustive reference for core settings and CLI flags. If you want a
smaller starter example, see [Configuration](configuration.md). If you want a guided
walkthrough, start with [Getting started](getting-started.md).

## Migration note

The hook/policy lifecycle is now the only supported build-facing customization surface.
Migrate legacy step-list edits to grouped `releaseIOPolicy*` keys, `releaseIOHooks*`, and
resource-aware custom plugins built around `releaseResourceHooks`.

Use the grouped names in `build.sbt`. The older flat names were removed in the breaking cleanup,
and the grouped names are now the canonical sbt key labels.

## Grouped key migration

| Removed name | Replacement |
| -------- | ---------------------- |
| `releaseIOCrossBuild` | `releaseIOBehaviorCrossBuild` |
| `releaseIOSkipPublish` | `releaseIOBehaviorSkipPublish` |
| `releaseIOInteractive` | `releaseIOBehaviorInteractive` |
| `releaseIODefaultTagExistsAnswer` | `releaseIODefaultsTagExistsAnswer` |
| `releaseIOVersionFile` | `releaseIOVersioningFile` |
| `releaseIOReadVersion` | `releaseIOVersioningReadVersion` |
| `releaseIOVersionFileContents` | `releaseIOVersioningFileContents` |
| `releaseIOTagName` | `releaseIOVcsTagName` |
| `releaseIOCommitMessage` | `releaseIOVcsReleaseCommitMessage` |
| `releaseIOPublishArtifactsAction` | `releaseIOPublishAction` |
| `releaseIORuntimeVersion` | `releaseIORuntimeCurrentVersion` |
| `releaseIOEnablePush` | `releaseIOPolicyEnablePush` |
| `releaseIOBeforeTagHooks` | `releaseIOHooksBeforeTag` |
| `releaseIOAfterPublishHooks` | `releaseIOHooksAfterPublish` |

> **Coming from sbt-release?** The original plugin enables interactive prompts by default.
> This plugin defaults to `releaseIOBehaviorInteractive := false` — decision points that
> have no configured answer **fail fast** instead of prompting.
>
> You have two options:
> - `releaseIOBehaviorInteractive := true` — restore the guided sbt-release-style
>   experience where the plugin prompts for versions, confirmation, and push decisions.
> - `with-defaults` CLI flag — auto-accept safe built-in defaults without prompting
>   and without enabling interactive mode. Useful for CI.
>
> The two can be combined: when both are active, `with-defaults` pre-answers prompts
> that would otherwise appear.

## Behavior settings

| Setting | Type | Default | Description |
| ------- | ---- | ------- | ----------- |
| `releaseIOBehaviorCrossBuild` | `Boolean` | `false` | Cross-build steps per `crossScalaVersions` |
| `releaseIOBehaviorSkipPublish` | `Boolean` | `false` | Skip the publish step entirely at runtime |
| `releaseIOBehaviorInteractive` | `Boolean` | `false` | Enable interactive prompting in `run` mode |

When interactive mode is enabled and no decision default is configured, four prompts may appear:

| Prompt | When | Default |
| ------ | ---- | ------- |
| `Do you want to continue (y/n)? [n]` | Snapshot dependencies detected | no (abort) |
| `Push changes to the remote repository (y/n)? [y]` | Before pushing | yes |
| `Tag [<name>] exists! Overwrite, keep or abort or enter a new tag (o/k/a)? [a]` | Tag already exists | abort |
| `Error while checking remote. Still continue (y/n)? [n]` | Remote check fails or times out | no (abort) |

When interactive is `false` (the default) and no decision default is set: snapshot-dependency and tag-conflict issues raise errors, push is skipped, and remote-check failures abort. The `with-defaults` CLI flag pre-answers all prompts with safe defaults without enabling interactive mode.

## Decision-default settings

| Setting | Type | Default | Description |
| ------- | ---- | ------- | ----------- |
| `releaseIODefaultsTagExistsAnswer` | `Option[String]` | `None` | Pre-answer for tag conflicts: `"o"` (overwrite), `"k"` (keep existing), `"a"` (abort), or a replacement tag name. `None` = prompt or abort |
| `releaseIODefaultsSnapshotDependenciesAnswer` | `Option[Boolean]` | `None` | Pre-answer for snapshot dependencies: `true` = continue, `false` = abort. `None` = prompt or abort |
| `releaseIODefaultsRemoteCheckFailureAnswer` | `Option[Boolean]` | `None` | Pre-answer when remote check fails: `true` = continue, `false` = abort. `None` = prompt or abort |
| `releaseIODefaultsUpstreamBehindAnswer` | `Option[Boolean]` | `None` | Pre-answer when branch is behind upstream: `true` = continue, `false` = abort. `None` = prompt or abort |
| `releaseIODefaultsPushAnswer` | `Option[Boolean]` | `None` | Pre-answer for push: `true` = push, `false` = skip push. `None` = prompt or skip |

## Versioning settings

| Setting | Type | Default | Description |
| ------- | ---- | ------- | ----------- |
| `releaseIOVersioningFile` | `File` | `baseDirectory / "version.sbt"` | Path to the version file |
| `releaseIOVersioningUseGlobal` | `Boolean` | `true` | Read/write `ThisBuild / version` instead of project-scoped `version` |
| `releaseIOVersioningReadVersion` | `File => IO[String]` | parses `version := "x.y.z"` | Read a version from the version file |
| `releaseIOVersioningFileContents` | `(File, String) => IO[String]` | writes `ThisBuild / version := "x.y.z"` | Produce version-file contents for a new version |
| `releaseIOVersioningBump` | `Version.Bump` | `Next` | Version bump strategy |
| `releaseIOVersioningReleaseVersion` | `String => String` | strips qualifier/snapshot | Compute the release version from the current one |
| `releaseIOVersioningNextVersion` | `String => String` | bumps and appends `-SNAPSHOT` | Compute the next development version |

## VCS settings

| Setting | Type | Default | Description |
| ------- | ---- | ------- | ----------- |
| `releaseIOVcsTagName` | `String` | `s"v${version.value}"` | Git tag name |
| `releaseIOVcsTagComment` | `String` | `s"Releasing ${version.value}"` | Git tag comment |
| `releaseIOVcsReleaseCommitMessage` | `String` | `s"Setting release version to ${version.value}"` | Release-version commit message |
| `releaseIOVcsNextCommitMessage` | `String` | `s"Setting next version to ${version.value}"` | Next-version commit message |
| `releaseIOVcsSign` | `Boolean` | `false` | GPG-sign tags and commits |
| `releaseIOVcsSignOff` | `Boolean` | `false` | Add `Signed-off-by` to commits |
| `releaseIOVcsIgnoreUntrackedFiles` | `Boolean` | `false` | Ignore untracked files in the clean check |
| `releaseIOVcsRemoteCheckTimeout` | `FiniteDuration` | `60.seconds` | Timeout for the remote reachability check before push |

## Publish settings

| Setting | Type | Default | Description |
| ------- | ---- | ------- | ----------- |
| `releaseIOPublishAction` | `Unit` | `publish` | Task that performs the publish |
| `releaseIOPublishChecks` | `Boolean` | `true` | Validate `publishTo` / `skip` before publish |

## Runtime and diagnostics

| Setting | Type | Default | Description |
| ------- | ---- | ------- | ----------- |
| `releaseIODiagnosticsSnapshotDependencies` | `Seq[ModuleID]` | auto-resolved | SNAPSHOT dependencies used by validation |
| `releaseIORuntimeCurrentVersion` | `String` | scope-aware `version` | Reads the current release version from live sbt state |

## Hook and policy settings

These settings compile into the built-in lifecycle for both `releaseIO` and
`releaseIO check`.

`releaseIOBehaviorSkipPublish` skips publish at runtime even if the phase still exists.
`releaseIOPolicyEnablePublish` removes the publish phase from the compiled lifecycle entirely,
so `releaseIOHooksBeforePublish` / `releaseIOHooksAfterPublish` do not exist when it is `false`.

| Setting | Type | Default | Description |
| ------- | ---- | ------- | ----------- |
| `releaseIOPolicyEnableSnapshotDependenciesCheck` | `Boolean` | `true` | Include `check-snapshot-dependencies` |
| `releaseIOPolicyEnableRunClean` | `Boolean` | `true` | Include `run-clean` |
| `releaseIOPolicyEnableRunTests` | `Boolean` | `true` | Include `run-tests` |
| `releaseIOPolicyEnableTagging` | `Boolean` | `true` | Include `tag-release` |
| `releaseIOPolicyEnablePublish` | `Boolean` | `true` | Include `publish-artifacts` |
| `releaseIOPolicyEnablePush` | `Boolean` | `true` | Include `push-changes` |
| `releaseIOHooksAfterCleanCheck` | `Seq[ReleaseHookIO]` | `Seq.empty` | Hooks after `check-clean-working-dir` |
| `releaseIOHooksBeforeVersionResolution` | `Seq[ReleaseHookIO]` | `Seq.empty` | Hooks before `inquire-versions` |
| `releaseIOHooksAfterVersionResolution` | `Seq[ReleaseHookIO]` | `Seq.empty` | Hooks after `inquire-versions` |
| `releaseIOHooksBeforeReleaseVersionWrite` | `Seq[ReleaseHookIO]` | `Seq.empty` | Hooks before `set-release-version` |
| `releaseIOHooksAfterReleaseVersionWrite` | `Seq[ReleaseHookIO]` | `Seq.empty` | Hooks after `set-release-version` |
| `releaseIOHooksBeforeReleaseCommit` | `Seq[ReleaseHookIO]` | `Seq.empty` | Hooks before `commit-release-version` |
| `releaseIOHooksAfterReleaseCommit` | `Seq[ReleaseHookIO]` | `Seq.empty` | Hooks after `commit-release-version` |
| `releaseIOHooksBeforeTag` | `Seq[ReleaseHookIO]` | `Seq.empty` | Hooks before `tag-release` |
| `releaseIOHooksAfterTag` | `Seq[ReleaseHookIO]` | `Seq.empty` | Hooks after `tag-release` |
| `releaseIOHooksBeforePublish` | `Seq[ReleaseHookIO]` | `Seq.empty` | Hooks before `publish-artifacts` |
| `releaseIOHooksAfterPublish` | `Seq[ReleaseHookIO]` | `Seq.empty` | Hooks after `publish-artifacts` |
| `releaseIOHooksBeforeNextVersionWrite` | `Seq[ReleaseHookIO]` | `Seq.empty` | Hooks before `set-next-version` |
| `releaseIOHooksAfterNextVersionWrite` | `Seq[ReleaseHookIO]` | `Seq.empty` | Hooks after `set-next-version` |
| `releaseIOHooksBeforeNextCommit` | `Seq[ReleaseHookIO]` | `Seq.empty` | Hooks before `commit-next-version` |
| `releaseIOHooksAfterNextCommit` | `Seq[ReleaseHookIO]` | `Seq.empty` | Hooks after `commit-next-version` |
| `releaseIOHooksBeforePush` | `Seq[ReleaseHookIO]` | `Seq.empty` | Hooks before `push-changes` |
| `releaseIOHooksAfterPush` | `Seq[ReleaseHookIO]` | `Seq.empty` | Hooks after `push-changes` |

## Version bump types

| Bump | Example | Description |
| ---- | ------- | ----------- |
| `Major` | `1.0.0 -> 2.0.0` | Bump major version |
| `Minor` | `1.0.0 -> 1.1.0` | Bump minor version |
| `Bugfix` | `1.0.0 -> 1.0.1` | Bump patch version |
| `Nano` | `1.0.0.0 -> 1.0.0.1` | Bump nano version |
| `Next` | `1.0.0 -> 1.0.1`, `1.0-RC1 -> 1.0-RC2` | Increment the last component (patch for stable, qualifier for pre-release) |
| `NextStable` | `1.0.0 -> 1.0.1`, `1.0-RC1 -> 1.0` | For stable: same as `Next`. For pre-release: drop the qualifier |

## CLI

### Subcommands

| Subcommand | Effect |
| ---------- | ------ |
| _(none)_ | Run the full release |
| `help` | Print usage, flags, examples, and docs links |
| `check` | Run a preflight with no release side effects |

### Flags

| Flag | Effect |
| ---- | ------ |
| `with-defaults` | Use built-in default answers instead of prompting |
| `skip-tests` | Skip the `run-tests` step |
| `cross` | Enable cross-building |
| `release-version <ver>` | Override the release version |
| `next-version <ver>` | Override the next snapshot version |
| `default-tag-exists-answer <o\|k\|a\|<tag-name>>` | Auto-answer tag-conflict handling |
| `default-snapshot-dependencies-answer <y\|n>` | Auto-answer snapshot-dependency confirmation |
| `default-remote-check-failure-answer <y\|n>` | Auto-answer remote-check failure confirmation |
| `default-upstream-behind-answer <y\|n>` | Auto-answer upstream-behind confirmation |
| `default-push-answer <y\|n>` | Auto-answer the final push confirmation |
