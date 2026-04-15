# Recipes (core)

## Cross-build releases

Enable cross-building so version-sensitive steps run once per distinct
`crossScalaVersions` value:

```scala
// build.sbt
releaseIOBehaviorCrossBuild := true
```

Or pass the `cross` flag on the command line:

```bash
sbt "releaseIO cross"
```

### Which steps are cross-built?

| Step                          | Cross-built? | Why                                             |
| ----------------------------- | ------------ | ----------------------------------------------- |
| `check-snapshot-dependencies` | Yes          | A SNAPSHOT may exist only under one Scala version |
| `run-tests`                   | Yes          | Tests must pass for every target version        |
| `publish-artifacts`           | Yes          | Artifacts are published per Scala version       |
| All other built-in steps      | No           | Version files, commits, tags, and push are universal |

### Lifecycle

1. The current Scala version is captured before the first iteration.
2. The configured `crossScalaVersions` are normalized to distinct values while preserving
   first-seen order.
3. For each distinct version, the project is reloaded with that version and the step runs.
4. After all iterations (or on error), the entry Scala version is restored.

Both the `validate` and `execute` phases are cross-built. This differs from sbt-release, which only cross-builds actions — a SNAPSHOT dependency present only under a non-default Scala version can slip through in sbt-release but is caught here.

### Custom steps

Only hooks attached to the `publish-artifacts` phase inherit cross-build when `cross`
(or `releaseIOBehaviorCrossBuild := true`) is enabled. Hooks around all other lifecycle
phases still run once per release. `ReleaseResourceHookIO` follows the same phase-level
rule as plain hooks:

```scala
releaseIOHooksBeforePublish += ReleaseHookIO.action("verify-publish-env") { ctx =>
  IO.blocking(ctx.state.log.info("[release-io] verifying publish environment"))
}
```

## CI/CD integration

The plugin defaults to non-interactive mode (`releaseIOBehaviorInteractive := false`), so it works in CI without additional configuration. Pass `with-defaults` to suppress the remaining version prompts, and supply versions explicitly:

```bash
sbt "releaseIO with-defaults release-version 1.0.0 next-version 1.1.0-SNAPSHOT"
```

If CI runs tests in a prior step, add `skip-tests` to avoid running them again:

```bash
sbt "releaseIO with-defaults skip-tests release-version 1.0.0 next-version 1.1.0-SNAPSHOT"
```

### GitHub Actions example

```yaml
jobs:
  release:
    runs-on: ubuntu-latest
    steps:
      - uses: actions/checkout@v4
        with:
          fetch-depth: 0  # avoid false positives in the behind-remote check on shallow clones
      - uses: actions/setup-java@v4
        with:
          distribution: temurin
          java-version: 21
      - name: Release
        env:
          RELEASE_VERSION: ${{ github.event.inputs.version }}
          NEXT_VERSION: ${{ github.event.inputs.next_version }}
        run: |
          sbt "releaseIO with-defaults release-version $RELEASE_VERSION next-version $NEXT_VERSION"
```

For the full list of CLI flags and subcommands, see [Settings reference — CLI](reference.md#cli).

## Local rehearsal

To rehearse a release locally, disable push, skip publish, run `check`, then run the real release with explicit versions:

```scala
// build.sbt
releaseIOPolicyEnablePush := false
releaseIOBehaviorSkipPublish := true
```

First run the preflight with no release side effects:

```bash
sbt "releaseIO check with-defaults"
```

Or with explicit versions:

```bash
sbt "releaseIO check with-defaults release-version 1.0.0 next-version 1.1.0-SNAPSHOT"
```

`check` has no release side effects: no version-file writes, commits, tags, publish, or push. When runtime hook state cannot still change them, it also resolves versions and tag names; otherwise it marks them as not evaluated. With cross-build validation enabled, sbt may temporarily switch Scala versions during validation and then restore the entry version.

Then run the real release with explicit versions so the tag name and commit count are
predictable:

```bash
sbt "releaseIO with-defaults release-version 1.0.0 next-version 1.1.0-SNAPSHOT"
```

The command creates two local commits (`commit-release-version` and `commit-next-version`)
and one tag (`v1.0.0`), but does not publish artifacts (because
`releaseIOBehaviorSkipPublish := true`) or push to the remote (because
`releaseIOPolicyEnablePush := false`). Inspect the result:

```bash
git log --oneline -5
git tag
cat version.sbt
```

To clean up after the rehearsal run, verify that the last two commits are the release
commits, then delete the tag and roll back:

```bash
git log -2 --oneline         # should show the two release commits
git tag -d v1.0.0
git reset --hard HEAD~2
```

For rollback in other scenarios (partial release, push already happened), see
[Recovery and rollback](operations.md#recovery-and-rollback).
