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
addSbtPlugin("io.github.scalauser12" % "sbt-release-io-monorepo" % "0.5.3")
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

Each subproject needs a `version.sbt` file (e.g., `core/version.sbt`, `api/version.sbt`) containing `version := "0.1.0-SNAPSHOT"`. The plugin reads and writes these files during the release. The file path and format can be customized — see [Version settings](configuration.md#version-settings).

For working examples, see [scala-monorepo-demo](https://github.com/scalauser12/scala-monorepo-demo) and [files-monorepo-demo](https://github.com/scalauser12/files-monorepo-demo).
