# Tagging, versions, and cross-build (monorepo)

## Per-project tags

Each released project gets its own tag:

```
core/v1.0.0
api/v0.5.0
```

Customize the format:

```scala
releaseIOMonorepoTagName := ((name, ver) => s"release/$name/$ver")
```

Each project keeps its own `version.sbt`, and CLI overrides are always expressed as `project=version` pairs:

```bash
sbt "releaseIOMonorepo core api with-defaults release-version core=1.0.0 release-version api=2.0.0 next-version core=1.1.0-SNAPSHOT next-version api=2.1.0-SNAPSHOT"
```

## Cross-build support

When the `cross` flag is active (or `releaseIOMonorepoCrossBuild := true`), steps with `enableCrossBuild = true` run once per entry in the project's `crossScalaVersions`.

Steps with cross-build enabled by default: `check-snapshot-dependencies`, `run-tests`, `publish-artifacts`.

Each project uses its own `crossScalaVersions`. A project with `Seq("2.13.12", "2.12.18")` runs cross-built steps twice; a project with only `Seq("2.12.18")` runs once. Empty `crossScalaVersions` with cross-build enabled raises an error.
