# Getting started (core)

## Features

- **Two-phase release steps**: Each step has a `validate` phase (preflight checks) and an `execute` phase (actions), both running in cats-effect `IO`
- **Policy and hook customization**: Extend the built-in release flow with grouped `releaseIOPolicy*` keys and `releaseIOHooks*`
- **Better error handling**: Graceful failure handling with the IO monad
- **Cross-build support**: Run both validation and execution phases across multiple Scala versions
- **Resource-safe custom plugins**: Acquire shared resources (HTTP clients, temp dirs, etc.) once for the entire release with guaranteed cleanup via `Resource[IO, T]`
- **Non-interactive by default**: Suggested versions are accepted automatically, blocking safety
  checks abort, and push is skipped unless a decision default or `with-defaults` says otherwise.
  Set `releaseIOBehaviorInteractive := true` to re-enable guided prompts — see
  [Behavior settings](reference.md#behavior-settings)
- **Configurable**: Settings for commit messages, GPG signing, version bumping, and publish gating

> **Note:** This plugin supports Git only. If your project uses Mercurial or Subversion, see [sbt-release](https://github.com/sbt/sbt-release).

## Installation

Add to `project/plugins.sbt`:

```scala
addSbtPlugin("io.github.scalauser12" % "sbt-release-io" % "0.13.6")
```

The project needs a `version.sbt` file:

```scala
ThisBuild / version := "0.1.0-SNAPSHOT"
```

The plugin reads and writes this file during the release. The file path and format can be customized — see [Custom version formats](configuration.md#custom-version-formats).

> **Note:** On Linux, run sbt under a UTF-8 locale (e.g. `LANG=C.UTF-8`). With a non-UTF-8
> locale the JVM cannot pass non-ASCII strings to git intact, so the plugin fails fast when
> a tag name or commit message cannot cross the process boundary, rather than silently
> corrupting it.

## Prepare a safe local rehearsal

Keep publishing and pushing out of the lifecycle while learning the command:

```scala
// build.sbt
releaseIOPolicyEnablePublish := false
releaseIOPolicyEnablePush    := false
```

The command requires a Git repository, and its clean-working-tree validation includes the build
files you just added. A full release also creates two Git commits, so configure an author identity.
Initialize Git if necessary, then commit `project/plugins.sbt`, the version file, and the rehearsal
settings before running `check`; that setup commit verifies the required identity:

```bash
git init                       # only for a project that is not already a Git repository
git add project/plugins.sbt version.sbt build.sbt
git commit -m "Configure sbt-release-io rehearsal"
```

If the project already has uncommitted work, commit or stash it instead of adding unrelated
changes to this setup commit.

## Usage

Start by inspecting the built-in command help:

```bash
sbt "releaseIO help"
```

Run a preflight to validate the release setup without performing release actions:

```bash
sbt "releaseIO check with-defaults"
```

`check` runs release-step validations and reports the planned release with no release side effects:
no version-file writes, commits, tags, publish, or push. It still invokes validation functions,
including `precondition` hooks, so custom validation code should not perform durable external
side effects. When runtime hooks can no longer change them, `check` also resolves versions and
tag names; otherwise it marks them as not evaluated. With cross-build validation enabled, sbt
may temporarily switch Scala versions during validation and then restore the entry version.

Run the release (versions computed from `version.sbt`):

```bash
sbt "releaseIO with-defaults"
```

With the rehearsal policies above, this full release remains local: it writes the version file,
creates the release commits and tag, but neither publishes nor pushes. Review the rollback steps
in [Operations](operations.md#rollback-push-has-not-happened) before running it in a repository
whose local history matters.

Default version resolution strips `-SNAPSHOT` to produce the release version (for example,
`0.1.0-SNAPSHOT` → `0.1.0`) and bumps the bugfix component for the next snapshot
(→ `0.1.1-SNAPSHOT`). Non-interactive full releases accept those suggested versions even without
`with-defaults`; that flag supplies the built-in answers for the other release decisions,
including a yes fallback for push when neither `default-push-answer` nor
`releaseIODefaultsPushAnswer` supplies one. The rehearsal push policy removes the push phase
entirely, so the flag cannot re-enable it. To bump a different component, set
`releaseIOVersioningBump`
(see [Version bump types](reference.md#version-bump-types)), or pass `release-version` /
`next-version` to override explicitly. If a release fails mid-way, see
[Recovery and rollback](operations.md#recovery-and-rollback).

Before a production release, remove the rehearsal policies and configure:

- `publishTo` for every publishing project (or `publish / skip := true` where appropriate), plus
  the credentials required by the target repository
- a writable tracking remote and upstream branch that permit branch updates and, when tagging is
  enabled, permit tag updates and support atomic multi-ref pushes

The default tagged release pushes the branch and tag together with `git push --atomic`. A remote
without that capability can pass the earlier upstream and tag checks but fail at the final push
after artifacts have been published.

Once `releaseIOPolicyEnablePush` is enabled again, `with-defaults` supplies a yes fallback only
when neither `default-push-answer` nor `releaseIODefaultsPushAnswer` provides an answer. Pass
`default-push-answer n` or configure `releaseIODefaultsPushAnswer := Some(false)` when a production
run should not push automatically.

Or specify versions explicitly:

```bash
sbt "releaseIO with-defaults release-version 1.0.0 next-version 1.1.0-SNAPSHOT"
```

Additional command-line options:

```bash
# Skip tests
sbt "releaseIO with-defaults skip-tests"

# Enable cross-building
sbt "releaseIO with-defaults cross"

# Auto-answer the tag-exists prompt
sbt "releaseIO with-defaults default-tag-exists-answer o"

# Preflight with explicit versions
sbt "releaseIO check with-defaults release-version 1.0.0 next-version 1.1.0-SNAPSHOT"
```

For the full list of CLI flags and subcommands, see [Settings reference — CLI](reference.md#cli).

For a concrete rehearsal that disables remote phases via policy keys and adds lifecycle hooks,
see [Customization walkthrough](customization-walkthrough.md).

## Default release steps

The default release process includes:

1. **initialize-vcs** - Detect and initialize VCS (Git)
2. **check-clean-working-dir** - Verify no uncommitted changes
3. **check-snapshot-dependencies** - Verify no snapshot dependencies
4. **inquire-versions** - Determine release and next versions
5. **tag-preflight** - Detect tag conflicts before any version write or commit
6. **run-clean** - Clean project build outputs (project-scoped `clean`)
7. **run-tests** - Run tests (unless `skip-tests`)
8. **set-release-version** - Update version.sbt to release version
9. **commit-release-version** - Commit version change
10. **tag-release** - Create Git tag
11. **publish-artifacts** - Publish to repository
12. **set-next-version** - Update version.sbt to next snapshot
13. **commit-next-version** - Commit version change
14. **push-changes** - Push commits and tags to remote

These names are the stable built-in phase names surfaced by `releaseIO help`, `check`, and the
hook documentation.

## What to read next

- Safe local rehearsal with hooks and policy keys:
  [Customization walkthrough](customization-walkthrough.md)
- Starter `build.sbt` patterns and common configuration recipes:
  [Configuration](configuration.md)
- Full settings and CLI catalog:
  [Settings reference](reference.md)
- Hooks, resource-aware custom plugins, and recipes:
  [Customization](customization.md)
- Validate/execute semantics and execution model details:
  [Concepts](concepts.md)
- Rollback and recovery after a failed release:
  [Operations](operations.md)
