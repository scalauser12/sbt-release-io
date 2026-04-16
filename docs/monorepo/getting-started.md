# Getting started (monorepo)

## Features

- **Per-project release steps**: Steps run once per subproject in topological (dependency) order
- **Change detection**: Git-based [change detection](change-detection.md) of which projects changed since the last release tag, with pluggable custom detectors
- **Per-project failure isolation**: A failing project is marked failed without aborting the current step's remaining projects; once that step finishes, later steps are skipped entirely for the whole release (see [Concepts](concepts.md))
- **Two-phase execution**: Setup establishes project selection first, then the remaining release steps validate before main mutations begin (see [Concepts](concepts.md))
- **Per-project tags**: Each released project gets its own tag (for example `core/v1.0.0`)
- **Cross-build support**: Steps like test and publish run once per `crossScalaVersions` entry
- **Resource-safe custom plugins**: `MonorepoReleasePluginLike[T]` acquires a shared resource (HTTP client, temp dir, etc.) once for the entire release with guaranteed cleanup

> **Note:** This plugin supports Git only. If your project uses Mercurial or Subversion, see [sbt-release](https://github.com/sbt/sbt-release).

## Installation

Add to `project/plugins.sbt`:

```scala
addSbtPlugin("io.github.scalauser12" % "sbt-release-io-monorepo" % "0.11.1")
```

> **Note:** This page describes the current published monorepo contract in `v0.11.1`; see
> [CHANGELOG.md](../CHANGELOG.md) for the full release history and upgrade notes.

This installs the monorepo plugin plus the transitive core settings surface, so
`releaseIOMonorepo`, `releaseIOMonorepo*`, and shared/core `releaseIO*` settings are
available once the plugin is enabled. In the documented monorepo setup, the supported release
workflow still uses `releaseIOMonorepo`.

Enable on your root project in `build.sbt`:

```scala
lazy val core = (project in file("core"))

lazy val api = (project in file("api"))
  .dependsOn(core)

lazy val root = (project in file("."))
  .aggregate(core, api)
  .enablePlugins(MonorepoReleasePlugin)
  .settings(
    // Disable push and publish during initial setup — re-enable when ready
    releaseIOMonorepoPolicyEnablePush    := false,
    releaseIOMonorepoPolicyEnablePublish := false
  )
```

By default, each subproject needs a `version.sbt` file (e.g., `core/version.sbt`, `api/version.sbt`) containing `version := "0.1.0-SNAPSHOT"`. The plugin reads and writes these files during the release. The file path and format can be customized — see [Versioning settings](reference.md#versioning-settings).

This starter path disables push and publish so your first run stays local. Re-enable them once `publishTo` and your remote release workflow are ready.

If you are migrating from an older configuration, move any shared root version file setup to per-project `version.sbt` files and replace any global CLI overrides with `project=version` overrides.

For working examples, see [scala-monorepo-demo](https://github.com/scalauser12/scala-monorepo-demo) and [files-monorepo-demo](https://github.com/scalauser12/files-monorepo-demo).

For a concrete rehearsal that combines change detection, downstream inclusion, and explicit
project selectors, see [Selective release walkthrough](selective-release-walkthrough.md).

## Usage

Start by inspecting the built-in command help:

```bash
sbt "releaseIOMonorepo help"
```

Run a preflight to validate the release setup without side effects:

```bash
sbt "releaseIOMonorepo check with-defaults"
```

`check` resolves the selected projects, computes versions and tags when their inputs are stable, runs release-step validations, and reports the planned release with no release side effects: no version-file writes, commits, tags, publish, or push. With cross-build validation enabled, sbt may temporarily switch Scala versions during validation and then restore the entry version.

For resource-aware custom plugins, `check` stays resource-free: it validates only hook phases whose validation context is stable without replaying earlier hook executes, and it never acquires the shared plugin resource or executes resource-backed actions. See [Customization](customization.md) for the execution-model details.

Run the first local release (changed projects detected automatically, versions computed from each subproject's `version.sbt`):

```bash
sbt "releaseIOMonorepo with-defaults"
```

With the starter settings above, this performs a local release only: it computes versions, writes version files, creates the release and next-version commits, and tags each selected project, but it does not publish artifacts or push to the remote. Remove those two policy settings once you are ready to enable publish and push. For details, see [Change detection](change-detection.md). If a release fails mid-way, see [Recovery and rollback](operations.md#recovery-and-rollback).

When a per-project step fails, the remaining projects in that same step still finish before the release stops. Later steps are then skipped globally. See [Concepts](concepts.md) for the full failure-propagation rules.

Or select projects and specify versions explicitly:

```bash
sbt "releaseIOMonorepo core with-defaults release-version core=1.0.0 next-version core=1.1.0-SNAPSHOT"
```

Use `all-changed` to override change detection and release all configured projects.

Additional examples:

```bash
# Preflight with explicit project and versions
sbt "releaseIOMonorepo check core with-defaults release-version core=1.0.0 next-version core=1.1.0-SNAPSHOT"

# Multiple projects with per-project versions
sbt "releaseIOMonorepo api core with-defaults release-version api=2.0.0 release-version core=1.0.0 next-version api=2.1.0-SNAPSHOT next-version core=1.1.0-SNAPSHOT"
```

If a project id collides with a CLI keyword or subcommand, select it with `project <id>`:

```bash
sbt "releaseIOMonorepo project cross with-defaults release-version cross=1.0.0 next-version cross=1.1.0-SNAPSHOT"
```

For the full list of CLI flags, subcommands, version override syntax, and selector syntax, see [Usage](usage.md).

## What to read next

- End-to-end setup from scratch:
  [First release walkthrough](walkthrough.md)
- Selective rehearsal with change detection and explicit selectors:
  [Selective release walkthrough](selective-release-walkthrough.md)
- CLI grammar, selectors, flags, and override syntax:
  [Usage](usage.md)
- Starter patterns and recipes:
  [Configuration](configuration.md)
- Exhaustive settings catalog:
  [Settings reference](reference.md)
- Hooks, resource-aware custom plugins, and recipes:
  [Customization](customization.md)
- Execution model, failure isolation, and ordering:
  [Concepts](concepts.md)
- Rollback and recovery:
  [Operations](operations.md)
