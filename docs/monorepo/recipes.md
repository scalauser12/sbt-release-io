# Recipes (monorepo)

## Cross-build releases

Enable cross-building so version-sensitive steps run once per `crossScalaVersions` for each selected project:

```scala
// build.sbt
releaseIOMonorepoCrossBuild := true
```

Or pass the `cross` flag on the command line:

```bash
sbt "releaseIOMonorepo cross"
```

### Which steps are cross-built?

| Step                          | Cross-built? | Why                                               |
| ----------------------------- | ------------ | ------------------------------------------------- |
| `check-snapshot-dependencies` | Yes          | A SNAPSHOT may exist only under one Scala version  |
| `run-tests`                   | Yes          | Tests must pass for every target version           |
| `publish-artifacts`           | Yes          | Artifacts are published per Scala version          |
| All other built-in steps      | No           | Version files, commits, tags, and push are universal |

### Lifecycle

For each selected project, cross-built steps follow this lifecycle:

1. The current Scala version is captured before the first iteration.
2. For each version in `crossScalaVersions`, the project is reloaded with that version and the step runs.
3. After all iterations (or on error), the entry Scala version is restored.

Both the `validate` and `execute` phases are cross-built.

### Custom steps

Opt in to cross-building with the builder API:

```scala
MonorepoStepIO
  .perProject("my-step")
  .withCrossBuild
  .executeAction((ctx, project) => ...)
```

## CI/CD integration

The plugin defaults to non-interactive mode (`releaseIOMonorepoInteractive := false`), so it works in CI without additional configuration. Pass `with-defaults` to suppress the remaining prompts, and supply versions explicitly.

### Per-project version overrides

```bash
sbt "releaseIOMonorepo with-defaults release-version core=1.0.0 api=2.0.0 next-version core=1.1.0-SNAPSHOT api=2.1.0-SNAPSHOT"
```

### Global version mode

When `releaseIOMonorepoUseGlobalVersion := true`, pass a single version:

```bash
sbt "releaseIOMonorepo with-defaults release-version 1.0.0 next-version 1.1.0-SNAPSHOT"
```

### GitHub Actions example

```yaml
jobs:
  release:
    runs-on: ubuntu-latest
    steps:
      - uses: actions/checkout@v4
        with:
          fetch-depth: 0  # full history for change detection and tag lookup
      - uses: actions/setup-java@v4
        with:
          distribution: temurin
          java-version: 21
      - name: Release
        env:
          RELEASE_VERSION: ${{ github.event.inputs.version }}
          NEXT_VERSION: ${{ github.event.inputs.next_version }}
        run: |
          sbt "releaseIOMonorepo with-defaults release-version $RELEASE_VERSION next-version $NEXT_VERSION"
```

> **Note:** `fetch-depth: 0` is important — change detection uses `git diff` against the last tag, so shallow clones may produce incorrect results.

### Useful CI flags

| Flag           | Effect                                            |
| -------------- | ------------------------------------------------- |
| `with-defaults`| Use default answers for all prompts               |
| `skip-tests`   | Skip the `run-tests` step                         |
| `cross`        | Enable cross-building                             |
| `all-changed`  | Release all changed projects (skip interactive selection) |

## Local rehearsal

To rehearse a release locally, remove `push-changes`, skip publish, run `check`, then run the real release with explicit versions:

```scala
// build.sbt
releaseIOMonorepoProcess    := releaseIOMonorepoProcess.value.filterNot(_.name == "push-changes")
releaseIOMonorepoSkipPublish := true
```

First run the preflight with no release side effects:

```bash
sbt "releaseIOMonorepo check core with-defaults release-version core=1.0.0 next-version core=1.1.0-SNAPSHOT"
```

`check` has no release side effects: no version-file writes, commits, tags, publish, or push. With cross-build validation enabled, sbt may temporarily switch Scala versions during validation and then restore the entry version.

Then run the real release:

```bash
sbt "releaseIOMonorepo core with-defaults release-version core=1.0.0 next-version core=1.1.0-SNAPSHOT"
```

The second command creates local commits and tags but does not publish artifacts or push to the remote. If you use global version mode, replace the per-project overrides with `release-version 1.0.0 next-version 1.1.0-SNAPSHOT`.

Inspect the result:

```bash
git log --oneline -5
git tag
cat version.sbt
```

To clean up after the rehearsal run, see [Recovery and rollback](operations.md#recovery-and-rollback).
