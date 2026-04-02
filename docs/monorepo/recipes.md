# Recipes (monorepo)

## Cross-build releases

Enable cross-building so version-sensitive steps run once per `crossScalaVersions` for each selected project:

```scala
// build.sbt
releaseIOMonorepoBehaviorCrossBuild := true
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

The plugin defaults to non-interactive mode (`releaseIOMonorepoBehaviorInteractive := false`), so it works in CI without additional configuration. Pass `with-defaults` to suppress the remaining prompts, and supply versions explicitly.

### Per-project version overrides

```bash
sbt "releaseIOMonorepo with-defaults release-version core=1.0.0 release-version api=2.0.0 next-version core=1.1.0-SNAPSHOT next-version api=2.1.0-SNAPSHOT"
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
          CORE_RELEASE_VERSION: ${{ github.event.inputs.core_version }}
          CORE_NEXT_VERSION: ${{ github.event.inputs.core_next_version }}
        run: |
          sbt "releaseIOMonorepo with-defaults release-version core=$CORE_RELEASE_VERSION next-version core=$CORE_NEXT_VERSION"
```

> **Note:** `fetch-depth: 0` is important — change detection uses `git diff` against the last tag, so shallow clones may produce incorrect results.

For the full list of CLI flags and subcommands, see [Usage](usage.md).

## Local rehearsal

To rehearse a release locally, disable push, skip publish, run `check`, then run the real release with explicit versions:

```scala
// build.sbt
releaseIOMonorepoPolicyEnablePush := false
releaseIOMonorepoBehaviorSkipPublish := true
```

First run the preflight with no release side effects:

```bash
sbt "releaseIOMonorepo check with-defaults"
```

Or with explicit project and versions:

```bash
sbt "releaseIOMonorepo check core with-defaults release-version core=1.0.0 next-version core=1.1.0-SNAPSHOT"
```

`check` has no release side effects: no version-file writes, commits, tags, publish, or
push. With cross-build validation enabled, sbt may temporarily switch Scala versions during
validation and then restore the entry version.

`releaseIOMonorepoBehaviorSkipPublish := true` keeps the publish phase available but skips it at
execution time. If you want to remove publish from the compiled lifecycle entirely,
use `releaseIOMonorepoPolicyEnablePublish := false` instead.

Then run the real release:

```bash
sbt "releaseIOMonorepo with-defaults"
```

The second command creates local commits and tags but does not publish artifacts or push to the remote.

Inspect the result:

```bash
git log --oneline -5
git tag
cat core/version.sbt
cat api/version.sbt
```

To clean up after the rehearsal run, see [Recovery and rollback](operations.md#recovery-and-rollback).

## Targeted project rehearsal

When change detection is too broad for the question you want to answer, keep the same safe local
settings but drive the plan with explicit selectors and version overrides.

In `build.sbt`:

```scala
import _root_.cats.effect.IO
import _root_.io.release.monorepo.MonorepoGlobalHookIO

releaseIOMonorepoPolicyEnablePush := false
releaseIOMonorepoPolicyEnablePublish := false
releaseIOMonorepoPolicyEnableRunClean := false
releaseIOMonorepoHooksAfterSelection +=
  MonorepoGlobalHookIO.action("print-selected-projects")(ctx =>
    IO.println(s"[monorepo] selected: ${ctx.currentProjects.map(_.name).mkString(", ")}")
  )
```

Then rehearse one project directly:

```bash
sbt "releaseIOMonorepo check api with-defaults release-version api=1.1.0 next-version api=1.2.0-SNAPSHOT"
```

This path is useful when:

- you want to validate one project's version overrides
- you want to confirm selector behavior without involving the full changed set
- you want a smaller rehearsal before running a broader change-detection flow

For a full hook-first example that combines change detection and downstream inclusion, see
[Selective release walkthrough](selective-release-walkthrough.md).
