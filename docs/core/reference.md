# Settings reference (core)

This page is the exhaustive reference for core settings and CLI flags. If you want a
smaller starter example, see [Configuration](configuration.md). If you want a guided
walkthrough, start with [Getting started](getting-started.md).

In `.scala` build sources under `project/`, import grouped keys from
`ReleasePluginIO.autoImport.*`. That surface owns both the core-only keys and the shared
`releaseIO*` settings. In `.sbt` files, the keys are auto-imported via the plugin's
`autoImport` — no import needed.

> **Coming from sbt-release?** The original plugin enables interactive prompts by default.
> This plugin defaults to `releaseIOBehaviorInteractive := false`: suggested release and next
> versions are accepted automatically, blocking safety checks abort when they need a decision,
> and push is skipped when it has no configured answer.
>
> You have two options:
> - `releaseIOBehaviorInteractive := true` — re-enable interactive prompts for versions,
>   confirmation, and push decisions.
> - `with-defaults` CLI flag — apply built-in decisions without prompting and without enabling
>   interactive mode. It accepts the suggested versions and supplies the built-in yes answer for
>   push only when neither `default-push-answer` nor `releaseIODefaultsPushAnswer` provides an
>   answer, while unsafe conditions such as snapshot dependencies, tag conflicts, and remote
>   failures still abort.
>
> The two can be combined: when both are active, `with-defaults` pre-answers prompts
> that would otherwise appear.

## Behavior settings

| Setting | Type | Default | Description |
| ------- | ---- | ------- | ----------- |
| `releaseIOBehaviorCrossBuild` | `Boolean` | `false` | Cross-build steps per `crossScalaVersions` |
| `releaseIOBehaviorSkipPublish` | `Boolean` | `false` | Skip the publish step body and its `beforePublish` / `afterPublish` hooks at runtime |
| `releaseIOBehaviorInteractive` | `Boolean` | `false` | Enable interactive prompting during a full release |

When interactive mode is enabled, `with-defaults` is absent, and versions are not supplied on the
command line, up to two version prompts may appear:

- `Release version [<suggested>] :`
- `Next version [<suggested>] :`

Five decision-prompt types may also appear when their corresponding defaults are not configured.
The complete interactive prompt surface is therefore five decision-prompt types plus two version
prompt types:

| Prompt | When | Default |
| ------ | ---- | ------- |
| `Do you want to continue (y/n)? [n]` | Snapshot dependencies detected | no (abort) |
| `Push changes to the remote repository (y/n)? [y]` | Before pushing | yes |
| `Tag [<name>] exists! Overwrite, keep or abort or enter a new tag (o/k/a)? [a]` | Tag already exists | abort |
| `Error while checking remote. Still continue (y/n)? [n]` | Remote check fails or times out | no (abort) |
| `The upstream branch has unmerged commits. A subsequent push may fail! Continue (y/n)? [n]` | Local branch is behind upstream | no (abort) |

When interactive is `false` (the default) and no decision default is set:

- suggested release and next versions are accepted
- snapshot-dependency and tag-conflict issues raise errors
- push is skipped
- remote-check failures and upstream-behind checks abort

The `with-defaults` CLI flag applies the prompt defaults without enabling interactive mode:
suggested versions are accepted, unsafe continuations are declined, and push defaults to accepted
only when `default-push-answer` and `releaseIODefaultsPushAnswer` are both unset.

## Shared decision-default settings

| Setting | Type | Default | Description |
| ------- | ---- | ------- | ----------- |
| `releaseIODefaultsTagExistsAnswer` | `Option[String]` | `None` | Pre-answer for tag conflicts: `"o"` (overwrite), `"k"` (keep existing), `"a"` (abort), or a replacement tag name. `None` = prompt or abort |
| `releaseIODefaultsSnapshotDependenciesAnswer` | `Option[Boolean]` | `None` | Pre-answer for snapshot dependencies: `true` = continue, `false` = abort. `None` = prompt or abort |
| `releaseIODefaultsRemoteCheckFailureAnswer` | `Option[Boolean]` | `None` | Pre-answer when remote check fails: `true` = continue, `false` = abort. `None` = prompt or abort |
| `releaseIODefaultsUpstreamBehindAnswer` | `Option[Boolean]` | `None` | Pre-answer when branch is behind upstream: `true` = continue, `false` = abort. `None` = prompt or abort |
| `releaseIODefaultsPushAnswer` | `Option[Boolean]` | `None` | Pre-answer for push: `true` = push, `false` = skip push. `None` = prompt or skip |

## Shared and core versioning settings

| Setting | Type | Default | Description |
| ------- | ---- | ------- | ----------- |
| `releaseIOVersioningFile` | `File` | `baseDirectory / "version.sbt"` | Path to the version file |
| `releaseIOVersioningUseGlobal` | `Boolean` | `true` | Apply version updates at `ThisBuild / version` instead of project-scoped `version`; aggregated publish targets must still match the single core release version |
| `releaseIOVersioningReadVersion` | `File => IO[String]` | parses `version := "x.y.z"` | Read a version from the version file |
| `releaseIOVersioningFileContents` | `(File, String) => IO[String]` | writes `ThisBuild / version := "x.y.z"`, or `version := "x.y.z"` when `releaseIOVersioningUseGlobal` is `false` | Produce version-file contents for a new version |
| `releaseIOVersioningBump` | `Version.Bump` | `Next` | Version bump strategy |
| `releaseIOVersioningReleaseVersion` | `String => String` | strips qualifier/snapshot | Compute the release version from the current one |
| `releaseIOVersioningNextVersion` | `String => String` | bumps and appends `-SNAPSHOT` | Compute the next development version |

## Shared and core VCS settings

| Setting | Type | Default | Description |
| ------- | ---- | ------- | ----------- |
| `releaseIOVcsTagName` | `String` | `s"v${releaseIORuntimeCurrentVersion.value}"` | Git tag name |
| `releaseIOVcsTagComment` | `String` | `s"Releasing ${releaseIORuntimeCurrentVersion.value}"` | Git tag comment |
| `releaseIOVcsReleaseCommitMessage` | `String` | `s"Setting release version to ${releaseIORuntimeCurrentVersion.value}"` | Release-version commit message |
| `releaseIOVcsNextCommitMessage` | `String` | `s"Setting next version to ${releaseIORuntimeCurrentVersion.value}"` | Next-version commit message |
| `releaseIOVcsSign` | `Boolean` | `false` | GPG-sign tags and commits |
| `releaseIOVcsSignOff` | `Boolean` | `false` | Add `Signed-off-by` to commits |
| `releaseIOVcsIgnoreUntrackedFiles` | `Boolean` | `false` | Ignore untracked files in the clean check |
| `releaseIOVcsRemoteCheckTimeout` | `FiniteDuration` | `60.seconds` | Timeout for the remote reachability check before push |

## Shared and core publish settings

| Setting | Type | Default | Description |
| ------- | ---- | ------- | ----------- |
| `releaseIOPublishAction` | `Unit` | `publish.value` | Task run for each eligible target selected from its aggregate graph |
| `releaseIOPublishChecks` | `Boolean` | `true` | Validate aggregate eligibility, versions, and `publishTo` before release execution |

The core command releases one version. Every non-skipped project reached through
`releaseIOPublishAction` aggregation must have that effective version; use the monorepo plugin
when aggregate children need independent versions.

The publish step evaluates `publish / skip` for every aggregate target, then runs only the eligible
scoped `releaseIOPublishAction` tasks in one selected sbt task graph. A skipped child therefore does
not run a custom publish action even when that action does not consult `publish / skip` itself.
Only aggregate action roots are filtered: an explicit dependency on another project's task inside
an eligible custom action remains part of that action's task graph.

When `releaseIOPublishChecks` is `true`, skip eligibility, destinations, and aggregate versions are
validated before release mutations, so mismatches visible then fail early. When it is `false`,
those upfront probes are disabled and `publish / skip` is evaluated live immediately before
publish. The execute-time aggregate-version guard is always enforced: disabling checks cannot
authorize independently versioned children, and version drift after checked validation is still
caught. A mismatch detected at this late boundary can leave the release commit and tag in place.
Before-publish hooks may already have run, but no selected `releaseIOPublishAction` runs for the
rejected aggregate/cross iteration; earlier cross iterations may already have published.

## Diagnostics and runtime

| Setting | Type | Default | Description |
| ------- | ---- | ------- | ----------- |
| `releaseIODiagnosticsSnapshotDependencies` | `Seq[ModuleID]` | auto-resolved | SNAPSHOT dependencies used by validation |
| `releaseIORuntimeCurrentVersion` | `String` | `(ThisBuild / version).value` if `releaseIOVersioningUseGlobal`, else `version.value` | Reads the current release version from live sbt state |

## Hook and policy settings

These settings compile into the built-in lifecycle for both `releaseIO` and
`releaseIO check`.

`releaseIOBehaviorSkipPublish` and `releaseIOPolicyEnablePublish` disable publish in different
ways — see [Disabling publish: policy vs behavior](configuration.md#disabling-publish-policy-vs-behavior).

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

`check` skips the execute phase and the built-in release mutations (version writes, commits, tags,
publish, and push). It still invokes validation functions and `precondition` hooks; custom
validation code is responsible for avoiding durable external side effects.

### Flags

| Flag | Effect |
| ---- | ------ |
| `with-defaults` | Use built-in default answers instead of prompting |
| `skip-tests` | Skip the `run-tests` step |
| `cross` | Enable cross-building |
| `release-version <ver>` | Override the release version |
| `next-version <ver>` | Override the next snapshot version |
| `default-tag-exists-answer o \| k \| a \| <new-tag>` | Auto-answer tag-conflict handling: `o` (overwrite), `k` (keep), `a` (abort), or a replacement tag name |
| `default-snapshot-dependencies-answer <y\|n>` | Auto-answer snapshot-dependency confirmation |
| `default-remote-check-failure-answer <y\|n>` | Auto-answer remote-check failure confirmation |
| `default-upstream-behind-answer <y\|n>` | Auto-answer upstream-behind confirmation |
| `default-push-answer <y\|n>` | Auto-answer the final push confirmation |
