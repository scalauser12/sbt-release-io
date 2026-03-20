# Usage (monorepo)

## Command

```
sbt releaseIOMonorepo [project...] [flags] [version overrides]
```

## Flags

| Flag | Description |
|------|-------------|
| `with-defaults` | Skip all interactive prompts, use computed versions |
| `skip-tests` | Skip the `run-tests` step |
| `cross` | Enable cross-build for steps with `enableCrossBuild = true` |
| `all-changed` | Override change detection: release all projects |

## Version overrides

Pin specific release or next versions per project:

```
release-version <project>=<version>
next-version <project>=<version>
```

In global version mode, use global overrides (without `project=`) to apply the same version to all projects:

```
release-version <version>
next-version <version>
```

Per-project overrides are not allowed in global version mode. Global overrides are not allowed in non-global mode.

## Examples

```bash
# Release all changed projects with default versions
sbt "releaseIOMonorepo with-defaults"

# Release only core and api
sbt "releaseIOMonorepo core api with-defaults"

# Pin versions per project
sbt "releaseIOMonorepo with-defaults release-version core=1.0.0 next-version core=1.1.0-SNAPSHOT"

# Pin versions globally (global version mode only)
sbt "releaseIOMonorepo with-defaults release-version 1.0.0 next-version 1.1.0-SNAPSHOT"

# Release all projects regardless of changes
sbt "releaseIOMonorepo all-changed with-defaults"

# Enable cross-building
sbt "releaseIOMonorepo cross with-defaults"

# Skip tests
sbt "releaseIOMonorepo skip-tests with-defaults"
```
