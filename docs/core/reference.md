# Settings reference (core)

This page is the exhaustive reference for core settings and CLI flags. If you want a smaller
starter configuration example, see [Configuration](configuration.md). If you want a tutorial,
start with [Getting started](getting-started.md).

All release settings use the `releaseIO` prefix:

| Setting                           | Type                           | Default                                  | Description                                                                   |
| --------------------------------- | ------------------------------ | ---------------------------------------- | ----------------------------------------------------------------------------- |
| `releaseIOProcess`                | `Seq[ReleaseStepIO]`           | `ReleaseSteps.defaults`                  | Legacy raw-process customization surface for release steps                    |
| `releaseIOCrossBuild`             | `Boolean`                      | `false`                                  | Cross-build steps per `crossScalaVersions`                                  |
| `releaseIOSkipPublish`            | `Boolean`                      | `false`                                  | Skip the publish step entirely                                                |
| `releaseIOInteractive`            | `Boolean`                      | `false`                                  | Enable interactive prompts                                                    |
| `releaseIOVersionFile`            | `File`                         | `baseDirectory / "version.sbt"`          | Path to the version file                                                      |
| `releaseIOUseGlobalVersion`       | `Boolean`                      | `true`                                   | Read and write `ThisBuild / version` instead of project-scoped `version`      |
| `releaseIOReadVersion`            | `File => IO[String]`           | parses `version := "x.y.z"`              | Read version from file                                                        |
| `releaseIOVersionFileContents`    | `(File, String) => IO[String]` | writes `ThisBuild / version := "x.y.z"`  | Produce version file contents                                                 |
| `releaseIOVersionBump`            | `Version.Bump`                 | `Next`                                   | Version bump strategy (see bump types below)                                  |
| `releaseIOVersion`                | `String => String`             | strips qualifier/snapshot                | Compute release version from current                                          |
| `releaseIONextVersion`            | `String => String`             | bumps + appends `-SNAPSHOT`              | Compute next dev version                                                      |
| `releaseIOTagName`                | `String`                       | `s"v${version.value}"`                   | Git tag name                                                                  |
| `releaseIOTagComment`             | `String`                       | `s"Releasing ${version.value}"`          | Git tag comment                                                               |
| `releaseIOCommitMessage`          | `String`                       | `s"Setting version to ${version.value}"` | Release version commit message                                                |
| `releaseIONextCommitMessage`      | `String`                       | `s"Setting version to ${version.value}"` | Next version commit message                                                   |
| `releaseIOVcsSign`                | `Boolean`                      | `false`                                  | GPG-sign tags and commits                                                     |
| `releaseIOVcsSignOff`             | `Boolean`                      | `false`                                  | Add `Signed-off-by` to commits                                              |
| `releaseIOIgnoreUntrackedFiles`   | `Boolean`                      | `false`                                  | Ignore untracked files in clean check                                         |
| `releaseIOVcsRemoteCheckTimeout`  | `FiniteDuration`               | `60.seconds`                             | Timeout for the remote reachability check (`git fetch`) before push           |
| `releaseIOPublishArtifactsAction` | `Unit`                         | `publish`                                | Task that performs the publish                                                |
| `releaseIOPublishArtifactsChecks` | `Boolean`                      | `true`                                   | Validate `publishTo`/`skip` before publish                                    |
| `releaseIOSnapshotDependencies`   | `Seq[ModuleID]`                | auto-resolved                            | SNAPSHOT deps for validation                                                  |
| `releaseIORuntimeVersion`         | `String`                       | scope-aware `version`                    | Reads `ThisBuild / version` or `version` based on `releaseIOUseGlobalVersion` |

## Hook / policy settings

These settings participate in the compiled hook-based flow when the raw process is left
alone. There are two separate legacy surfaces:

- the public raw-process setting `releaseIOProcess`
- the protected custom-plugin hooks `releaseProcess` / `releaseCheckProcess`

If the raw-process setting or the protected check-process hook is customized, both `run`
and `check` stay in legacy raw-process mode and ignore these keys. If only the protected
`releaseProcess` hook is customized, the real release run switches to legacy mode while
`check` stays on the plain configured process until `releaseCheckProcess` is also
customized.

`releaseIOSkipPublish` is the runtime execution flag that skips publish even if the
publish step is still present. `releaseIOEnablePublish` removes the publish phase from
the compiled hook-first lifecycle entirely, so `beforePublish` / `afterPublish` hooks do
not exist when it is `false`.

| Setting                                  | Type                 | Default      | Description                                                        |
| ---------------------------------------- | -------------------- | ------------ | ------------------------------------------------------------------ |
| `releaseIOEnableSnapshotDependenciesCheck` | `Boolean`          | `true`       | Include `check-snapshot-dependencies` in the compiled process      |
| `releaseIOEnableRunClean`                | `Boolean`            | `true`       | Include `run-clean` in the compiled process                        |
| `releaseIOEnableRunTests`                | `Boolean`            | `true`       | Include `run-tests` in the compiled process                        |
| `releaseIOEnableTagging`                 | `Boolean`            | `true`       | Include `tag-release` in the compiled process                      |
| `releaseIOEnablePublish`                 | `Boolean`            | `true`       | Include `publish-artifacts` in the compiled process                |
| `releaseIOEnablePush`                    | `Boolean`            | `true`       | Include `push-changes` in the compiled process                     |
| `releaseIOAfterCleanCheckHooks`          | `Seq[ReleaseHookIO]` | `Seq.empty`  | Hooks that run after `check-clean-working-dir`                     |
| `releaseIOBeforeVersionResolutionHooks`  | `Seq[ReleaseHookIO]` | `Seq.empty`  | Hooks that run before `inquire-versions`                           |
| `releaseIOAfterVersionResolutionHooks`   | `Seq[ReleaseHookIO]` | `Seq.empty`  | Hooks that run after `inquire-versions`                            |
| `releaseIOBeforeReleaseVersionWriteHooks`| `Seq[ReleaseHookIO]` | `Seq.empty`  | Hooks that run before `set-release-version`                        |
| `releaseIOAfterReleaseVersionWriteHooks` | `Seq[ReleaseHookIO]` | `Seq.empty`  | Hooks that run after `set-release-version`                         |
| `releaseIOBeforeReleaseCommitHooks`      | `Seq[ReleaseHookIO]` | `Seq.empty`  | Hooks that run before `commit-release-version`                     |
| `releaseIOAfterReleaseCommitHooks`       | `Seq[ReleaseHookIO]` | `Seq.empty`  | Hooks that run after `commit-release-version`                      |
| `releaseIOBeforeTagHooks`                | `Seq[ReleaseHookIO]` | `Seq.empty`  | Hooks that run before `tag-release` when tagging is enabled        |
| `releaseIOAfterTagHooks`                 | `Seq[ReleaseHookIO]` | `Seq.empty`  | Hooks that run after `tag-release` when tagging is enabled         |
| `releaseIOBeforePublishHooks`            | `Seq[ReleaseHookIO]` | `Seq.empty`  | Hooks that run before `publish-artifacts` when publish runs        |
| `releaseIOAfterPublishHooks`             | `Seq[ReleaseHookIO]` | `Seq.empty`  | Hooks that run after `publish-artifacts` when publish runs         |
| `releaseIOBeforeNextVersionWriteHooks`   | `Seq[ReleaseHookIO]` | `Seq.empty`  | Hooks that run before `set-next-version`                           |
| `releaseIOAfterNextVersionWriteHooks`    | `Seq[ReleaseHookIO]` | `Seq.empty`  | Hooks that run after `set-next-version`                            |
| `releaseIOBeforeNextCommitHooks`         | `Seq[ReleaseHookIO]` | `Seq.empty`  | Hooks that run before `commit-next-version`                        |
| `releaseIOAfterNextCommitHooks`          | `Seq[ReleaseHookIO]` | `Seq.empty`  | Hooks that run after `commit-next-version`                         |
| `releaseIOBeforePushHooks`               | `Seq[ReleaseHookIO]` | `Seq.empty`  | Hooks that run before `push-changes` when push is enabled          |
| `releaseIOAfterPushHooks`                | `Seq[ReleaseHookIO]` | `Seq.empty`  | Hooks that run after `push-changes` when push is enabled           |

## Version bump types

| Bump         | Example           | Description                                                 |
| ------------ | ----------------- | ----------------------------------------------------------- |
| `Major`      | 1.0.0 → 2.0.0     | Bump major version                                          |
| `Minor`      | 1.0.0 → 1.1.0     | Bump minor version                                          |
| `Bugfix`     | 1.0.0 → 1.0.1     | Bump bugfix/patch version                                   |
| `Nano`       | 1.0.0.0 → 1.0.0.1 | Bump nano version                                           |
| `Next`       | 1.0-RC1 → 1.0-RC2 | Increment next component including prerelease **(default)** |
| `NextStable` | 1.0-RC1 → 1.0     | Increment next component, remove prerelease qualifier       |

## CLI

### Subcommands

| Subcommand | Effect |
|------------|--------|
| _(none)_ | Run the full release |
| `help` | Print usage, flags, examples, and docs links |
| `check` | Run a preflight with no release side effects: resolve versions and tags, run step validations, and print a summary — without writing version files, creating commits or tags, publishing, or pushing |

### Flags

| Flag | Effect |
| ---- | ------ |
| `with-defaults` | Use default answers for prompts |
| `skip-tests` | Skip the `run-tests` step |
| `cross` | Enable cross-building |
| `release-version <ver>` | Override the release version |
| `next-version <ver>` | Override the next snapshot version |
| `default-tag-exists-answer <o\|k\|a\|<tag-name>>` | Auto-answer the tag-exists prompt |
