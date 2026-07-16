# Concepts (core)

Use this page for the core plugin's execution model and the conceptual differences from
`sbt-release`. For the default built-in step list, see [Getting started](getting-started.md).

## Validate / execute model

The compiled core lifecycle is expressed in terms of `ProcessStep`, the internal
validate/execute runtime model that policies and hooks compile into. Each compiled step has two
phases:

- `validate: ReleaseContext => IO[ReleaseContext]`
- `execute: ReleaseContext => IO[ReleaseContext]`

Author-facing hooks use a narrower API: `ReleaseHookIO.validate` (and the resource-hook
variants) return `IO[Unit]`, while `ReleaseHookIO.execute` returns `IO[ReleaseContext]`. The
runtime adapts both into the internal `ProcessStep` model so the lifecycle keeps threading
`ReleaseContext` through every phase.

The release engine validates the planned lifecycle before it performs any release actions.
That means `releaseIO check` can run validations and print the plan without writing version
files, creating commits or tags, publishing, or pushing. When runtime hooks can no longer
change them, it also resolves versions and tags; otherwise it marks them as not evaluated.
Custom validation functions and `precondition` hooks still execute in this mode, so hook authors
should keep validation code free of durable external side effects.

## Aggregate publishing uses one release version

The core plugin resolves one release version for the command. When
`releaseIOPublishAction` aggregates into child projects, every child that will actually publish
must resolve `version` to that same release version. A skipped child is ignored, including when it
defines a custom `releaseIOPublishAction` that does not read `publish / skip`. Eligible scoped
publish actions run directly in one selected sbt task graph; the publish step does not re-expand
the unfiltered aggregate after deciding which targets may run. This filtering applies to aggregate
action roots only. If an eligible custom action explicitly depends on another project's task, that
dependency remains part of the action's sbt task graph and is the build author's responsibility.

With `releaseIOPublishChecks := true`, eligibility, `publishTo`, and aggregate versions are checked
upfront, so a mismatch observable during validation aborts before release side effects. With checks
disabled, validation does not evaluate `publish / skip` or `publishTo`; eligibility remains live at
execution. The single-version check is mandatory under either setting immediately before each
selected publish task graph runs. A mismatch introduced after checked validation, or first observed
with checks disabled, can therefore leave the release commit and tag in place. Before-publish hooks
may already have run, but no publish action runs for the rejected aggregate/cross iteration; earlier
cross iterations may already have published.

This remains true when `releaseIOVersioningUseGlobal := false`: that setting controls whether
version updates use `ThisBuild` or the current project scope; it does not turn the core command
into a per-project release. Align the child version, set `publish / skip := true`, disable
aggregation for `releaseIOPublishAction`, or use the monorepo plugin for independently versioned
projects.

## Execution model: sbt-release-io vs sbt-release

Both plugins share the same high-level structure: block the sbt command thread, and run
validation checks before mutating actions. The main difference is the effect model:

- `sbt-release` uses plain `State => State` functions composed with `Function.chain`
- `sbt-release-io` wraps the flow in cats-effect `IO` and runs it with `unsafeRunSync()`

### Shared structure

- **Synchronous blocking**: both plugins keep the sbt command thread until the release ends
- **Validation before actions**: both separate preflight checks from the mutating steps

### What IO adds

- **Resource safety**: `Resource.use` guarantees cleanup for shared resources
- **Composability**: hooks and internal process helpers can use normal cats-effect combinators
- **Hook validations cannot mutate context**: `ReleaseHookIO.validate` returns `IO[Unit]`, so a hook's pre-flight check cannot alter the release context (the `execute` phase still returns `IO[ReleaseContext]` and can update it)
- **Explicit blocking boundaries**: `IO.blocking` marks shell-outs and sbt task execution
- **Typed context threading**: `ReleaseContext` carries versions, VCS state, flags, and typed metadata
- **Cross-build validation**: both `validate` and `execute` phases can cross-build when enabled
- **Direct task execution**: built-in steps evaluate sbt tasks directly, including selected aggregate task graphs, instead of enqueuing commands like `+publish`. Cross-build iterates `crossScalaVersions` in Scala code rather than via the `+` command prefix.
- **Resource-aware custom plugins**: `ReleasePluginIOLike[T]` can acquire one shared resource for the full release

### IO-specific costs

- **Cats-effect runtime overhead**: the runtime keeps compute and blocking pools alive for the sbt session
- **Signal handling tradeoffs**: while `unsafeRunSync` blocks, interruption and finalizer behavior follows the cats-effect runtime rather than raw synchronous sbt code

### Summary

| Aspect                  | sbt-release                                 | sbt-release-io                                    |
| ----------------------- | ------------------------------------------- | ------------------------------------------------- |
| Effect system           | Plain `State => State` via `Function.chain` | `IO`-wrapped via `unsafeRunSync`                  |
| Internal step type      | `ReleaseStep(action, check)`                | `ProcessStep(validate: C => IO[C], execute: C => IO[C])` |
| Supported customization | Direct process editing and step surgery     | Policies, hooks, and resource hooks               |
| Resource management     | Manual                                      | `Resource.use` with guaranteed cleanup            |
| Cross-build validation  | Actions only                                | Both `validate` and `execute` phases              |
| Custom plugin resources | Not supported                               | `ReleasePluginIOLike[T]`                          |
| VCS support             | Git, Mercurial, Subversion                  | Git only                                          |
| Error handling          | `FailureCommand` sentinel in State          | `IO.raiseError` + `handleErrorWith`               |
| Composability           | `Function.chain`                            | Monadic (`for`/`flatMap`)                         |
