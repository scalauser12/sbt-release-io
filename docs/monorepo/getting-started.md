# Getting started (monorepo)

## Features

- **Per-project release steps**: Steps run once per subproject in topological (dependency) order
- **Change detection**: Git-based detection of which projects changed since the last release tag, with pluggable custom detectors
- **Per-project failure isolation**: A failing project is marked failed without aborting the current step's remaining projects; subsequent steps are skipped entirely
- **Two-phase execution**: All checks run before any actions, so failures are caught before version files or tags are modified
- **Tagging strategies**: Per-project tags (`core/v1.0.0`) or unified tags (`v1.0.0`)
- **Global version mode**: Optional single `version.sbt` at the root, with consistency validation across all projects
- **Cross-build support**: Steps like test and publish run once per `crossScalaVersions` entry
- **Resource-safe custom plugins**: `MonorepoReleasePluginLike[T]` acquires a shared resource (HTTP client, temp dir, etc.) once for the entire release with guaranteed cleanup

## Installation

Add to `project/plugins.sbt`:

```scala
addSbtPlugin("io.github.scalauser12" % "sbt-release-io-monorepo" % "0.6.0")
```

Enable on your root project in `build.sbt`:

```scala
lazy val core = (project in file("core"))
  .dependsOn(api)

lazy val api = (project in file("api"))

lazy val root = (project in file("."))
  .aggregate(core, api)
  .enablePlugins(MonorepoReleasePlugin)
```

By default, each subproject needs a `version.sbt` file (e.g., `core/version.sbt`, `api/version.sbt`) containing `version := "0.1.0-SNAPSHOT"`. The plugin reads and writes these files during the release. The file path and format can be customized — see [Version settings](configuration.md#version-settings).

If you enable `releaseIOMonorepoUseGlobalVersion := true`, configure a single root version file instead and pass one release version plus one next version for the entire release. In that mode, all projects participate together.

For working examples, see [scala-monorepo-demo](https://github.com/scalauser12/scala-monorepo-demo) and [files-monorepo-demo](https://github.com/scalauser12/files-monorepo-demo).

## Usage

Start by inspecting the built-in command help:

```bash
sbt "releaseIOMonorepo help"
```

Run a preflight with no release side effects for an explicit project selection:

```bash
sbt "releaseIOMonorepo check core with-defaults release-version core=1.0.0 next-version core=1.1.0-SNAPSHOT"
```

`check` resolves the selected projects, computes versions and tags, runs release-step validations, and reports the planned release with no release side effects: no version-file writes, commits, tags, publish, or push. With cross-build validation enabled, sbt may temporarily switch Scala versions during validation and then restore the entry version.

Run the actual release:

```bash
sbt "releaseIOMonorepo core with-defaults release-version core=1.0.0 next-version core=1.1.0-SNAPSHOT"
```

If you omit project names, the plugin uses change detection. Use `all-changed` to bypass change detection and include every configured project.

Examples:

```bash
# Detect changed projects automatically
sbt "releaseIOMonorepo with-defaults"

# Explicit project selection
sbt "releaseIOMonorepo api core with-defaults release-version api=2.0.0 core=1.0.0 next-version api=2.1.0-SNAPSHOT core=1.1.0-SNAPSHOT"

# Global version mode
sbt "releaseIOMonorepo check with-defaults release-version 1.0.0 next-version 1.1.0-SNAPSHOT"
```

Available flags:

| Flag | Effect |
| ---- | ------ |
| `with-defaults` | Use default answers for prompts |
| `skip-tests` | Skip the `run-tests` step |
| `cross` | Enable cross-building |
| `all-changed` | Select all configured projects without interactive change detection |

Version override syntax:

| Mode | Release override | Next override |
| ---- | ---------------- | ------------- |
| Per-project | `release-version core=1.0.0` | `next-version core=1.1.0-SNAPSHOT` |
| Global | `release-version 1.0.0` | `next-version 1.1.0-SNAPSHOT` |

`help` and `check` are reserved only when they appear as the first token after `releaseIOMonorepo`.
Avoid using project ids that collide with CLI keywords such as `with-defaults`, `skip-tests`, `cross`, `all-changed`, `release-version`, `next-version`, `help`, or `check`.
