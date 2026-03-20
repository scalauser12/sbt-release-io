# Operations (core)

## Recovery and rollback

### What each release step modifies

| Step                     | Modifies                       |
| ------------------------ | ------------------------------ |
| `set-release-version`    | `version.sbt` (working tree)   |
| `commit-release-version` | Local git history — one commit |
| `tag-release`            | Local git tag                  |
| `publish-artifacts`      | Remote artifact repository     |
| `set-next-version`       | `version.sbt` (working tree)   |
| `commit-next-version`    | Local git history — one commit |
| `push-changes`           | Remote git branch and tags     |

### Checking current state

```bash
git log --oneline -5   # see what commits the release made
git tag                # see what tags were created
cat version.sbt        # inspect the version file
```

### Rollback: push has not happened

```bash
# Delete the tag created by tag-release
git tag -d v1.0.0

# Undo commits (2 = commit-release-version + commit-next-version)
git reset --hard HEAD~2
```

If the release failed before `commit-next-version` (only one commit was made):

```bash
git tag -d v1.0.0
git reset --hard HEAD~1
```

### Rollback: push has already happened

```bash
# Delete the remote tag
git push origin :refs/tags/v1.0.0

# Safe revert (keeps history)
git revert HEAD     # revert commit-next-version
git revert HEAD~1   # revert commit-release-version
git push origin main
```

> **Note:** Published artifacts cannot be retracted from most repositories. Publish a corrected patch release instead.

## Migrating custom steps

If you are updating a custom plugin or build from an older release:

- rename `step.check` to `step.validate`
- rename `step.action` to `step.execute`
- replace `resourceStep(...)`, `resourceStepAction(...)`, `resourceStepWithCheck(...)`, `resourceStepWithValidation(...)`, and `resourceStepActionWithValidation(...)` factory methods with `ReleaseStepIO.resourceStep[T](name)` builder API
- replace string attributes with typed metadata via `ctx.withMetadata`, `ctx.metadata`, and `AttributeKey[A]`

## Testing

This plugin includes comprehensive scripted tests.

Run all tests:

```bash
sbt scripted
```

Run specific test:

```bash
sbt "core/scripted sbt-release-io/simple"
```

See `modules/core/src/sbt-test/README.md` for test documentation.

## Compatibility

- **sbt**: 1.12.3 and 2.0.0-RC9
- **Scala**: 2.12.21 and 3.8.1
- **cats-effect**: 3.7.0
- **VCS**: Git only (sbt-release also supports Mercurial and Subversion)

## Execution model: sbt-release-io vs sbt-release

Both plugins share the same high-level structure: block the sbt command thread, run validation checks before actions, execute steps sequentially, and manually drain enqueued commands (e.g., from `+publish`). The key difference is the effect system — sbt-release uses plain `State => State` functions composed with `Function.chain`, while sbt-release-io wraps everything in cats-effect `IO` and runs it with `unsafeRunSync()`.

### Shared structure

- **Synchronous blocking**: Both plugins hold the sbt command thread for the entire release. sbt-release composes all steps via `Function.chain`; sbt-release-io runs a single `unsafeRunSync()`. Neither returns control to sbt's event loop between steps.
- **Validation then execution**: sbt-release has `check`/`action` fields on `ReleaseStep`; sbt-release-io has `validate`/`execute` on `ReleaseStepIO`. Both run all checks before any actions.
- **Manual command draining**: Both plugins use `@tailrec` loops to drain enqueued commands — sbt-release in `releaseStepCommandAndRemaining`, sbt-release-io in `fromCommandAndRemaining`.

### What IO adds

- **Resource safety**: `Resource.use` guarantees cleanup (close connections, release locks) even on failure or interruption. sbt-release has no equivalent — cleanup requires manual `try/finally` in each step.
- **Composability**: Steps compose with `for`/`flatMap` and standard cats-effect combinators. Custom steps can use `IO.blocking`, `IO.race`, `IO.timeout`, retry logic, etc. sbt-release steps are opaque `State => State` functions with no built-in combinators.
- **Typed validation boundary**: The IO model's validation phase returns `IO[Unit]` (side-effect-aware but context-non-threading), making it explicit that checks cannot alter the release context. sbt-release uses the same `State => State` type for both checks and actions, relying on convention to keep checks read-only. `IO[Unit]` enforces this at the type level.
- **Explicit blocking boundaries**: `IO.blocking` marks which operations shell out to git or run sbt tasks. The cats-effect runtime dispatches these to a blocking thread pool, keeping the compute pool free. sbt-release runs everything on the sbt command thread with no distinction.
- **Typed context threading**: `ReleaseContext` carries VCS, versions, and typed metadata through the step chain with type safety. sbt-release uses untyped `State` attributes (`state.get(key)`) that can fail at runtime if a prior step didn't set the expected attribute.
- **Cross-build validation**: Both `validate` and `execute` phases are cross-built when `enableCrossBuild = true`. sbt-release only cross-builds actions, not preflight validation — a SNAPSHOT dependency present only under a non-default Scala version can slip through.
- **Custom plugins with resources**: `ReleasePluginIOLike[T]` lets you define a plugin parameterized by a resource type (HTTP client, temp directory, etc.) that is acquired once and shared across all steps.

### IO-specific costs

- **Cats-effect runtime overhead**: The global `IORuntime` creates compute and blocking thread pools that persist for the sbt session, even when no release is running.
- **Signal handling**: While `unsafeRunSync` blocks, sbt's interrupt handling is delayed. On graceful shutdown, cats-effect's runtime attempts to cancel running fibers and run `Resource` finalizers, but this is best-effort. sbt-release, blocking synchronously via `Function.chain`, responds to Ctrl+C more directly.

### Summary

| Aspect                  | sbt-release                                 | sbt-release-io                         |
| ----------------------- | ------------------------------------------- | -------------------------------------- |
| Effect system           | Plain `State => State` via `Function.chain` | `IO`-wrapped via `unsafeRunSync`       |
| Step type               | `ReleaseStep(action, check)`                | `ReleaseStepIO(validate, execute)`     |
| Resource management     | Manual                                      | `Resource.use` with guaranteed cleanup |
| Cross-build validation  | Actions only                                | Both `validate` and `execute` phases   |
| Custom plugin resources | Not supported                               | `ReleasePluginIOLike[T]`               |
| VCS support             | Git, Mercurial, Subversion                  | Git only                               |
| Error handling          | `FailureCommand` sentinel in State          | `IO.raiseError` + `handleErrorWith`    |
| Composability           | `Function.chain`                            | Monadic (`for`/`flatMap`)              |

The plugin ports sbt-release's types, settings, and execution model onto cats-effect IO, with no runtime dependency on sbt-release.

For contributing, see [../CONTRIBUTING.md](../CONTRIBUTING.md).
