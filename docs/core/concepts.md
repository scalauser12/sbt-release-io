# Concepts (core)

Use this page for the core plugin's execution model and the conceptual differences from
`sbt-release`. For the default built-in step list, see [Getting started](getting-started.md).

## Validate / execute model

Internally, the compiled core lifecycle is expressed in terms of `ProcessStep`, the internal
validate/execute runtime model that policies and hooks compile into. Each compiled step has two
phases:

- `validate: ReleaseContext => IO[Unit]`
- `execute: ReleaseContext => IO[ReleaseContext]`

The release engine validates the planned lifecycle before it performs any release actions.
That means `releaseIO check` can resolve versions and tags, run validations, and print the
plan without writing version files, creating commits or tags, publishing, or pushing.

Hook and policy settings compile into the validate/execute lifecycle that `releaseIO` runs.
The release command then executes the planned steps sequentially, threading `ReleaseContext`
through the run.

## Execution model: sbt-release-io vs sbt-release

Both plugins share the same high-level structure: block the sbt command thread, run
validation checks before actions, execute steps sequentially, and manually drain enqueued
commands such as `+publish`. The main difference is the effect model:

- `sbt-release` uses plain `State => State` functions composed with `Function.chain`
- `sbt-release-io` wraps the flow in cats-effect `IO` and runs it with `unsafeRunSync()`

### Shared structure

- **Synchronous blocking**: both plugins keep the sbt command thread until the release ends
- **Validation before actions**: both separate preflight checks from the mutating steps
- **Manual command draining**: both explicitly drain queued sbt commands for command-based steps

### What IO adds

- **Resource safety**: `Resource.use` guarantees cleanup for shared resources
- **Composability**: hooks and internal process helpers can use normal cats-effect combinators
- **Typed validation boundary**: `validate` returns `IO[Unit]`, so checks cannot mutate the release context
- **Explicit blocking boundaries**: `IO.blocking` marks shell-outs and sbt task execution
- **Typed context threading**: `ReleaseContext` carries versions, VCS state, flags, and typed metadata
- **Cross-build validation**: both `validate` and `execute` phases can cross-build when enabled
- **Resource-aware custom plugins**: `ReleasePluginIOLike[T]` can acquire one shared resource for the full release

### IO-specific costs

- **Cats-effect runtime overhead**: the runtime keeps compute and blocking pools alive for the sbt session
- **Signal handling tradeoffs**: while `unsafeRunSync` blocks, interruption and finalizer behavior follows the cats-effect runtime rather than raw synchronous sbt code

### Summary

| Aspect                  | sbt-release                                 | sbt-release-io                                    |
| ----------------------- | ------------------------------------------- | ------------------------------------------------- |
| Effect system           | Plain `State => State` via `Function.chain` | `IO`-wrapped via `unsafeRunSync`                  |
| Internal step type      | `ReleaseStep(action, check)`                | `ProcessStep(validate, execute)`              |
| Supported customization | Direct process editing and step surgery     | Policies, hooks, and resource hooks               |
| Resource management     | Manual                                      | `Resource.use` with guaranteed cleanup            |
| Cross-build validation  | Actions only                                | Both `validate` and `execute` phases              |
| Custom plugin resources | Not supported                               | `ReleasePluginIOLike[T]`                          |
| VCS support             | Git, Mercurial, Subversion                  | Git only                                          |
| Error handling          | `FailureCommand` sentinel in State          | `IO.raiseError` + `handleErrorWith`               |
| Composability           | `Function.chain`                            | Monadic (`for`/`flatMap`)                         |

The plugin ports sbt-release's types, settings, and execution model onto cats-effect IO,
with no runtime dependency on sbt-release.
