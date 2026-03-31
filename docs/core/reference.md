# Settings reference (core)

This page is the exhaustive reference for core settings and CLI flags. If you want a
smaller starter example, see [Configuration](configuration.md). If you want a guided
walkthrough, start with [Getting started](getting-started.md).

## Migration note

The hook/policy lifecycle is now the only supported build-facing customization surface.
Migrate legacy step-list edits to `releaseIOEnable*` policy keys, `releaseIO*Hooks`, and
resource-aware custom plugins built around `releaseResourceHooks`.

## Main settings

All release settings use the `releaseIO` prefix.

| Setting | Type | Default | Description |
| ------- | ---- | ------- | ----------- |
| `releaseIOCrossBuild` | `Boolean` | `false` | Cross-build steps per `crossScalaVersions` |
| `releaseIOSkipPublish` | `Boolean` | `false` | Skip the publish step entirely at runtime |
| `releaseIOInteractive` | `Boolean` | `false` | Enable interactive prompting in `run` mode |
| `releaseIODefaultTagExistsAnswer` | `Option[String]` | `None` | Default answer for tag-conflict handling |
| `releaseIODefaultSnapshotDependenciesAnswer` | `Option[Boolean]` | `None` | Default answer for snapshot-dependency confirmation |
| `releaseIODefaultRemoteCheckFailureAnswer` | `Option[Boolean]` | `None` | Default answer when the remote check fails |
| `releaseIODefaultUpstreamBehindAnswer` | `Option[Boolean]` | `None` | Default answer when the branch is behind upstream |
| `releaseIODefaultPushAnswer` | `Option[Boolean]` | `None` | Default answer for the final push prompt |
| `releaseIOVersionFile` | `File` | `baseDirectory / "version.sbt"` | Path to the version file |
| `releaseIOUseGlobalVersion` | `Boolean` | `true` | Read/write `ThisBuild / version` instead of project-scoped `version` |
| `releaseIOReadVersion` | `File => IO[String]` | parses `version := "x.y.z"` | Read a version from the version file |
| `releaseIOVersionFileContents` | `(File, String) => IO[String]` | writes `ThisBuild / version := "x.y.z"` | Produce version-file contents for a new version |
| `releaseIOVersionBump` | `Version.Bump` | `Next` | Version bump strategy |
| `releaseIOVersion` | `String => String` | strips qualifier/snapshot | Compute the release version from the current one |
| `releaseIONextVersion` | `String => String` | bumps and appends `-SNAPSHOT` | Compute the next development version |
| `releaseIOTagName` | `String` | `s"v${version.value}"` | Git tag name |
| `releaseIOTagComment` | `String` | `s"Releasing ${version.value}"` | Git tag comment |
| `releaseIOCommitMessage` | `String` | `s"Setting version to ${version.value}"` | Release-version commit message |
| `releaseIONextCommitMessage` | `String` | `s"Setting version to ${version.value}"` | Next-version commit message |
| `releaseIOVcsSign` | `Boolean` | `false` | GPG-sign tags and commits |
| `releaseIOVcsSignOff` | `Boolean` | `false` | Add `Signed-off-by` to commits |
| `releaseIOIgnoreUntrackedFiles` | `Boolean` | `false` | Ignore untracked files in the clean check |
| `releaseIOVcsRemoteCheckTimeout` | `FiniteDuration` | `60.seconds` | Timeout for the remote reachability check before push |
| `releaseIOPublishArtifactsAction` | `Unit` | `publish` | Task that performs the publish |
| `releaseIOPublishArtifactsChecks` | `Boolean` | `true` | Validate `publishTo` / `skip` before publish |
| `releaseIOSnapshotDependencies` | `Seq[ModuleID]` | auto-resolved | SNAPSHOT dependencies used by validation |
| `releaseIORuntimeVersion` | `String` | scope-aware `version` | Reads the current release version from live sbt state |

## Hook and policy settings

These settings compile into the built-in lifecycle for both `releaseIO` and
`releaseIO check`.

`releaseIOSkipPublish` skips publish at runtime even if the phase still exists.
`releaseIOEnablePublish` removes the publish phase from the compiled lifecycle entirely,
so `beforePublish` / `afterPublish` hooks do not exist when it is `false`.

| Setting | Type | Default | Description |
| ------- | ---- | ------- | ----------- |
| `releaseIOEnableSnapshotDependenciesCheck` | `Boolean` | `true` | Include `check-snapshot-dependencies` |
| `releaseIOEnableRunClean` | `Boolean` | `true` | Include `run-clean` |
| `releaseIOEnableRunTests` | `Boolean` | `true` | Include `run-tests` |
| `releaseIOEnableTagging` | `Boolean` | `true` | Include `tag-release` |
| `releaseIOEnablePublish` | `Boolean` | `true` | Include `publish-artifacts` |
| `releaseIOEnablePush` | `Boolean` | `true` | Include `push-changes` |
| `releaseIOAfterCleanCheckHooks` | `Seq[ReleaseHookIO]` | `Seq.empty` | Hooks after `check-clean-working-dir` |
| `releaseIOBeforeVersionResolutionHooks` | `Seq[ReleaseHookIO]` | `Seq.empty` | Hooks before `inquire-versions` |
| `releaseIOAfterVersionResolutionHooks` | `Seq[ReleaseHookIO]` | `Seq.empty` | Hooks after `inquire-versions` |
| `releaseIOBeforeReleaseVersionWriteHooks` | `Seq[ReleaseHookIO]` | `Seq.empty` | Hooks before `set-release-version` |
| `releaseIOAfterReleaseVersionWriteHooks` | `Seq[ReleaseHookIO]` | `Seq.empty` | Hooks after `set-release-version` |
| `releaseIOBeforeReleaseCommitHooks` | `Seq[ReleaseHookIO]` | `Seq.empty` | Hooks before `commit-release-version` |
| `releaseIOAfterReleaseCommitHooks` | `Seq[ReleaseHookIO]` | `Seq.empty` | Hooks after `commit-release-version` |
| `releaseIOBeforeTagHooks` | `Seq[ReleaseHookIO]` | `Seq.empty` | Hooks before `tag-release` |
| `releaseIOAfterTagHooks` | `Seq[ReleaseHookIO]` | `Seq.empty` | Hooks after `tag-release` |
| `releaseIOBeforePublishHooks` | `Seq[ReleaseHookIO]` | `Seq.empty` | Hooks before `publish-artifacts` |
| `releaseIOAfterPublishHooks` | `Seq[ReleaseHookIO]` | `Seq.empty` | Hooks after `publish-artifacts` |
| `releaseIOBeforeNextVersionWriteHooks` | `Seq[ReleaseHookIO]` | `Seq.empty` | Hooks before `set-next-version` |
| `releaseIOAfterNextVersionWriteHooks` | `Seq[ReleaseHookIO]` | `Seq.empty` | Hooks after `set-next-version` |
| `releaseIOBeforeNextCommitHooks` | `Seq[ReleaseHookIO]` | `Seq.empty` | Hooks before `commit-next-version` |
| `releaseIOAfterNextCommitHooks` | `Seq[ReleaseHookIO]` | `Seq.empty` | Hooks after `commit-next-version` |
| `releaseIOBeforePushHooks` | `Seq[ReleaseHookIO]` | `Seq.empty` | Hooks before `push-changes` |
| `releaseIOAfterPushHooks` | `Seq[ReleaseHookIO]` | `Seq.empty` | Hooks after `push-changes` |

## Version bump types

| Bump | Example | Description |
| ---- | ------- | ----------- |
| `Major` | `1.0.0 -> 2.0.0` | Bump major version |
| `Minor` | `1.0.0 -> 1.1.0` | Bump minor version |
| `Bugfix` | `1.0.0 -> 1.0.1` | Bump patch version |
| `Nano` | `1.0.0.0 -> 1.0.0.1` | Bump nano version |
| `Next` | `1.0-RC1 -> 1.0-RC2` | Increment the next component, including prerelease |
| `NextStable` | `1.0-RC1 -> 1.0` | Drop the prerelease qualifier |

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
