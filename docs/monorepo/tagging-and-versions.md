# Tagging, global version, and cross-build (monorepo)

## Tagging strategies

### PerProject (default)

Each released project gets its own tag:

```
core/v1.0.0
api/v0.5.0
```

Customize the format:

```scala
releaseIOMonorepoTagName := ((name, ver) => s"release/$name/$ver")
```

### Unified

A single tag covers the entire release:

```
v1.0.0
```

Requires all projects to have the same release version. The tag annotation lists all project names and versions.

> **Note:** The version consistency check applies only to the projects selected for release. When change detection is enabled, unchanged projects are excluded, so partial releases are possible — the unified tag will only reflect the changed subset. For true all-or-nothing unified releases, combine with Global Version mode or use the `all-changed` flag.

```scala
releaseIOMonorepoTagStrategy    := MonorepoTagStrategy.Unified
releaseIOMonorepoUnifiedTagName := (ver => s"release-v$ver")
```

## Global version mode

When `releaseIOMonorepoUseGlobalVersion := true`:

- All projects share the root `version.sbt` (the file defined by `releaseIOVersionFile`).
- Version file content uses `ThisBuild / version := "x.y.z"` instead of `version := "x.y.z"`.
- Per-project version overrides are rejected at parse time; use global overrides instead (`release-version 1.0.0`).
- Partial project selection is blocked (CLI validation returns an error if a subset is named).
- Change detection must select either all projects or none.

```scala
releaseIOMonorepoUseGlobalVersion := true
```

## Cross-build support

When the `cross` flag is active (or `releaseIOMonorepoCrossBuild := true`), steps with `enableCrossBuild = true` run once per entry in the project's `crossScalaVersions`.

Steps with cross-build enabled by default: `check-snapshot-dependencies`, `run-tests`, `publish-artifacts`.

Each project uses its own `crossScalaVersions`. A project with `Seq("2.13.12", "2.12.18")` runs cross-built steps twice; a project with only `Seq("2.12.18")` runs once. Empty `crossScalaVersions` with cross-build enabled raises an error.
