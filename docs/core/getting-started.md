# Getting started (core)

## Features

- **Two-phase release steps**: Each step has a `validate` phase (preflight checks) and an `execute` phase (actions), both running in cats-effect `IO`
- **Independent codebase**: Ports sbt-release's types, settings, and execution model onto cats-effect IO — no runtime dependency on sbt-release
- **Policy and hook customization**: Extend the built-in release flow with grouped `releaseIOPolicy*` keys and `releaseIOHooks*`
- **Better error handling**: Graceful failure handling with the IO monad
- **Cross-build support**: Run both validation and execution phases across multiple Scala versions
- **Resource-safe custom plugins**: Acquire shared resources (HTTP clients, temp dirs, etc.) once for the entire release with guaranteed cleanup via `Resource[IO, T]`
- **Non-interactive by default**: Unlike sbt-release, interactive prompts are disabled by default for CI safety. Set `releaseIOBehaviorInteractive := true` for the guided sbt-release-style experience — see [Behavior settings](reference.md#behavior-settings)
- **Configurable**: Comprehensive settings for commit messages, signing, version bumping, etc.

> **Note:** This plugin supports Git only. If your project uses Mercurial or Subversion, see [sbt-release](https://github.com/sbt/sbt-release).

## Installation

Add to `project/plugins.sbt`:

```scala
addSbtPlugin("io.github.scalauser12" % "sbt-release-io" % "0.11.1")
```

The project needs a `version.sbt` file containing `ThisBuild / version := "0.1.0-SNAPSHOT"`. The plugin reads and writes this file during the release. The file path and format can be customized — see [Custom version formats](configuration.md#custom-version-formats).

## Usage

Start by inspecting the built-in command help:

```bash
sbt "releaseIO help"
```

Run a preflight to validate the release setup without side effects:

```bash
sbt "releaseIO check with-defaults"
```

`check` runs release-step validations and reports the planned release with no release side effects: no version-file writes, commits, tags, publish, or push. When runtime hook state cannot still change them, it also resolves versions and tag names; otherwise it marks them as not evaluated. With cross-build validation enabled, sbt may temporarily switch Scala versions during validation and then restore the entry version.

Run the release (versions computed from `version.sbt`):

```bash
sbt "releaseIO with-defaults"
```

`with-defaults` strips `-SNAPSHOT` to produce the release version (e.g. `0.1.0-SNAPSHOT` → `0.1.0`) and bumps the bugfix component for the next snapshot (→ `0.1.1-SNAPSHOT`). To bump a different component, set `releaseIOVersioningBump` (see [Version bump types](reference.md#version-bump-types)), or pass `release-version` / `next-version` to override explicitly. If a release fails mid-way, see [Recovery and rollback](operations.md#recovery-and-rollback).

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
5. **run-clean** - Clean project build outputs (project-scoped `clean`)
6. **run-tests** - Run tests (unless `skip-tests`)
7. **set-release-version** - Update version.sbt to release version
8. **commit-release-version** - Commit version change
9. **tag-release** - Create Git tag
10. **publish-artifacts** - Publish to repository
11. **set-next-version** - Update version.sbt to next snapshot
12. **commit-next-version** - Commit version change
13. **push-changes** - Push commits and tags to remote

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
