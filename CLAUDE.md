# sbt-release-io

Scala/sbt plugin porting sbt-release to cats-effect IO. Public plugin modules:
**core** (single-project releases) and **monorepo** (multi-project releases with change
detection). Shared `releaseIO*` key/default support lives in `runtime` and is packaged through
the published core artifact.

## Build & Test

```bash
sbt compile                # compile all modules
sbt test                   # run all unit tests (MUnit)
sbt core/test              # core unit tests only
sbt monorepo/test          # monorepo unit tests only
sbt scripted               # run all scripted integration tests
sbt core/scripted          # core scripted tests only
sbt monorepo/scripted      # monorepo scripted tests only
```

### Cross-build (sbt 2)

```bash
./bin/sbt2-clean compile
./bin/sbt2-clean test
sbt -Dsbt.version=2.0.0-RC9 test
```

**Note:** Prefer `./bin/sbt2-clean ...` for local sbt 2 work. It runs from a clean tracked
snapshot, which avoids interference from IDE-generated files such as `project/metals.sbt`
or `.bloop/`. Use plain `sbt -Dsbt.version=2.0.0-RC9 ...` only when you explicitly need
the direct lane or you are working from a known-clean checkout/CI environment.

### Verification Expectations

- If a change affects runtime behavior, command execution, release flow, or source compatibility,
  verify it on both supported sbt lines:
  - sbt 1: `sbt -Dsbt.version=1.12.3 ...`
  - sbt 2: `./bin/sbt2-clean ...`
- If you touch version-specific test compat or source-split code, inspect and update both
  `scala-2` and `scala-3` source roots when applicable.

### Formatting

```bash
sbt scalafmtAll            # format Scala sources
sbt scalafmtSbt            # format .sbt and project/*.scala files
sbt scalafmtCheckAll       # check Scala source formatting
sbt scalafmtSbtCheck       # check sbt/build file formatting
```

## Project Structure

```
modules/
├── core/                                     # io.release, io.release.core.internal
│   ├── src/main/scala/io/release/            # Public API: ReleasePluginIO, ReleaseContext, ReleaseHookIO, ReleaseResourceHookIO, ReleaseComposer, VcsOps
│   ├── src/main/scala/io/release/core/internal/  # CoreLifecycle, CoreCommandExecution, CoreDefaultSettings, steps/
│   ├── src/test/scala/                       # Unit tests (MUnit)
│   ├── src/sbt-test/sbt-release-io/          # 40+ scripted integration tests
│   └── examples/                             # Example code
├── monorepo/                                 # io.release.monorepo, io.release.monorepo.internal
│   ├── src/main/scala/io/release/monorepo/   # Public API: MonorepoReleasePlugin, MonorepoContext, MonorepoHookIO, MonorepoResourceHookIO
│   ├── src/main/scala/io/release/monorepo/internal/  # MonorepoComposer, ChangeDetection, MonorepoProjectResolver, MonorepoSelectionResolver, DependencyGraph, MonorepoLifecycle, MonorepoCommandExecution
│   ├── src/test/scala/                       # Unit tests
│   ├── src/sbt-test/sbt-release-io-monorepo/ # 60+ scripted tests
│   └── examples/                             # Example code
├── runtime/                                  # io.release, io.release.runtime, io.release.vcs, io.release.version
│   └── src/main/scala/                       # Engine, shared key ownership, VCS adapter, version model
└── testkit/                                  # io.release
    └── src/main/scala/                       # TestAssertions, TestSupport, TestRepoFiles
docs/
├── core/       # Core plugin documentation
└── monorepo/   # Monorepo plugin documentation
```

Contributor-oriented overview (modules, command flow, glossary): [docs/ARCHITECTURE.md](docs/ARCHITECTURE.md).

## Key Source Files

### Core Module

| File | Purpose |
|------|---------|
| `core/ReleasePluginIO.scala` | Main sbt plugin (`ReleasePluginIOLike[T]`); auto-triggered; resource lifecycle, two-phase execution; grouped core keys exposed via the `ReleasePluginIOAutoImport` object |
| `core/ReleaseContext.scala` | Immutable context threaded through steps (versions, vcs, state, metadata) |
| `core/ReleaseHookIO.scala` | Hook case class for lifecycle customization |
| `core/ReleaseResourceHookIO.scala` | Resource-lifecycle hook for acquire/release around the run |
| `core/ReleaseComposer.scala` | Composes policies + hooks into the core release sequence |
| `core/internal/CoreLifecycle.scala` | Wires core policy/hook settings to the shared lifecycle compiler |
| `core/internal/CoreDecisionDefaultsCli.scala` | Resolves grouped decision-default settings and CLI overrides for core commands |
| `core/internal/steps/ReleaseSteps.scala` | 13 default steps (initialize-vcs → check-clean → inquire-versions → tag-release → publish-artifacts → push-changes); `private[release]` |

### Monorepo Module

| File | Purpose |
|------|---------|
| `monorepo/MonorepoReleasePlugin.scala` | Monorepo plugin (`noTrigger`, must be explicitly enabled on root) |
| `monorepo/MonorepoContext.scala` | Global context + `ProjectReleaseInfo` per project |
| `monorepo/MonorepoHookIO.scala` | Global and per-project hook types |
| `monorepo/MonorepoResourceHookIO.scala` | Global resource hook type for acquire/release around the run |
| `monorepo/internal/MonorepoComposer.scala` | Composes global and per-project steps into release sequence |
| `monorepo/internal/MonorepoCommandExecution.scala` | Monorepo command preparation, planning, and release/check orchestration |
| `monorepo/internal/MonorepoDecisionDefaultsCli.scala` | Monorepo CLI parsing for decision-default answers |
| `monorepo/internal/ChangeDetection.scala` | Git diff-based change detection with shared-paths support |
| `monorepo/internal/MonorepoProjectResolver.scala` | Dependency graph resolution and topological sorting |
| `monorepo/internal/MonorepoSelectionResolver.scala` | Project selection (by name or change detection) |

### Runtime Module

| File | Purpose |
|------|---------|
| `runtime/engine/ExecutionEngine.scala` | Runs compiled step sequence with context threading and tracked error recovery |
| `runtime/engine/LifecycleCompiler.scala` | Compiles policy settings + hooks into ordered step sequence |
| `runtime/engine/ProcessStep.scala` | `sealed trait ProcessStep[C, +I]` ADT (internal) |
| `runtime/engine/BuiltInStepRole.scala` | Typed role markers for built-in steps used in orchestration decisions |
| `runtime/TrackedContextHandle.scala` | Serialized, reentry-checked mutable checkpoint handle for tracked execution |
| `runtime/command/ReleaseCommandRunner.scala` | Shared command-boundary execution (incl. `runPreparedCommand`), logging, and final state handling |
| `ReleaseSharedKeys.scala` | Runtime-owned shared sbt setting/task keys reused by `core` and `monorepo` without duplicating key identity |
| `ReleaseSharedDefaultSettingsSupport.scala` | Runtime-owned shared default-setting logic reused by internal workflows and plugin setup |
| `runtime/workflow/VersionWorkflowSupport.scala` | Default version-file IO and publish validation helpers |
| `vcs/Git.scala` | Git VCS adapter with `IO.blocking` wrappers |
| `version/Version.scala` | Version model |

### Testkit Module

| File | Purpose |
|------|---------|
| `TestSupport.scala` | MUnit test harness base |
| `TestAssertions.scala` | IO-aware assertion helpers |
| `TestRepoFiles.scala` | Git-backed test repo fixtures |

## Conventions

- Scala 2.12 (sbt 1) and Scala 3 (sbt 2) cross-build
- Max line length: **100 columns** (enforced by scalafmt 3.10.7)
- Tests use **MUnit** with **munit-cats-effect** for IO assertions
- Scripted tests live under `src/sbt-test/` in each module
- All blocking operations wrapped in `IO.blocking`
- Immutable context threading — steps return updated context, no mutable state
- Hook-based customization is the supported build-facing model
- Keep changes narrow by module boundary: `modules/core` for single-project behavior,
  `modules/monorepo` for monorepo-specific behavior, `modules/runtime` for shared internals
- Always check README examples and `examples/` folders when planning code changes

## Architecture

### Two-Phase Execution

1. **Validation** (`releaseIO check`): runs all `validate` functions, no resource acquired, no side effects
2. **Execution** (`releaseIO run`): acquires resource via `Resource.use`, runs validate + execute, threads context

Core and monorepo command entry points share command-boundary cleanup, hook compilation,
decision-default resolution, and final state handling through the runtime command helpers.

### Customization Model

**Supported — Hook lifecycle:** Policy settings enable/disable phases (`releaseIOPolicyEnableRunTests`, `releaseIOPolicyEnablePublish`, etc.). Hook settings inject logic at semantic points (`releaseIOHooksBeforeTag`, `releaseIOHooksAfterPublish`, etc.). Grouped keys are the only supported build-facing settings surface. Compiled into an ordered step sequence at startup.

**Advanced internals:** Lower-level step types still exist for internals, tests, and custom plugin
helpers, but build-facing customization should use hooks, policies, and resource hooks.

### Monorepo Specifics

- Per-project failure isolation (global steps stop, per-project steps continue to next project)
- Topological ordering of projects based on dependency graph
- Change detection via `git diff` against last tag per project

## Dependencies

- `cats-effect 3.7.0` — async/resource management
- `munit 1.2.4` + `munit-cats-effect 2.2.0` — testing
- `sbt-scalafmt 2.5.6` — formatting
- `sbt-ci-release 1.11.2` — Maven Central publishing

## CI

GitHub Actions (`ci.yml`) runs format checks, unit tests on sbt 1 and sbt 2, scripted tests for
the core and monorepo plugins on both sbt versions, plus publish-local smoke for the published
plugin artifacts. Releases are published by GitHub Actions from pushed `v*` tags; treat that
workflow as the canonical release path.

Do not run `sbt ci-release`, publish to Maven Central manually, or modify release credentials or
secrets unless explicitly asked.

## Release Sequence

version bump → tag → push → GitHub Actions publishes → GitHub release notes
