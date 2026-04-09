# sbt-release-io

Scala/sbt plugin porting sbt-release to cats-effect IO. Two modules: **core** (single-project releases) and **monorepo** (multi-project releases with change detection).

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
sbt -Dsbt.version=2.0.0-RC9 compile
sbt -Dsbt.version=2.0.0-RC9 test
./bin/sbt2-clean test
```

**Note:** Use `./bin/sbt2-clean ...` from an IDE-managed checkout when local files such as
`project/metals.sbt` or `.bloop/` would otherwise affect the sbt 2 lane.

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
│   ├── src/sbt-test/sbt-release-io/          # 50+ scripted integration tests
│   └── examples/                             # Example code
├── monorepo/                                 # io.release.monorepo, io.release.monorepo.internal
│   ├── src/main/scala/io/release/monorepo/   # Public API: MonorepoReleasePlugin, MonorepoContext, MonorepoHookIO, MonorepoResourceHookIO
│   ├── src/main/scala/io/release/monorepo/internal/  # MonorepoComposer, ChangeDetection, MonorepoProjectResolver, MonorepoSelectionResolver, DependencyGraph, MonorepoLifecycle, MonorepoCommandExecution
│   ├── src/test/scala/                       # Unit tests
│   ├── src/sbt-test/sbt-release-io-monorepo/ # 60+ scripted tests
│   └── examples/                             # Example code
├── runtime/                                  # io.release, io.release.runtime, io.release.vcs, io.release.version
│   └── src/main/scala/                       # Engine, VCS adapter, version model (shared by core + monorepo)
└── testkit/                                  # io.release
    └── src/main/scala/                       # TestAssertions, TestSupport, TestRepoFiles
docs/
├── core/       # Core plugin documentation
└── monorepo/   # Monorepo plugin documentation
```

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
| `core/internal/steps/ReleaseSteps.scala` | 13 default steps (initialize-vcs → check-clean → inquire-versions → tag-release → publish-artifacts → push-changes); `private[release]` |

### Monorepo Module

| File | Purpose |
|------|---------|
| `monorepo/MonorepoReleasePlugin.scala` | Monorepo plugin (`noTrigger`, must be explicitly enabled on root) |
| `monorepo/MonorepoContext.scala` | Global context + `ProjectReleaseInfo` per project |
| `monorepo/MonorepoHookIO.scala` | Global and per-project hook types |
| `monorepo/MonorepoResourceHookIO.scala` | Global resource hook type for acquire/release around the run |
| `monorepo/internal/MonorepoComposer.scala` | Composes global and per-project steps into release sequence |
| `monorepo/internal/ChangeDetection.scala` | Git diff-based change detection with shared-paths support |
| `monorepo/internal/MonorepoProjectResolver.scala` | Dependency graph resolution and topological sorting |
| `monorepo/internal/MonorepoSelectionResolver.scala` | Project selection (by name or change detection) |

### Runtime Module

| File | Purpose |
|------|---------|
| `runtime/engine/ExecutionEngine.scala` | Runs compiled step sequence with context threading |
| `runtime/engine/StepKernel.scala` | Step validate/execute kernel; cancellation-safe execution |
| `runtime/engine/LifecycleCompiler.scala` | Compiles policy settings + hooks into ordered step sequence |
| `runtime/engine/ProcessStep.scala` | `sealed trait ProcessStep[C, +I]` ADT (internal) |
| `ReleaseKeys.scala` | Shared sbt setting/task keys used by core + monorepo |
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
- Always check README examples and `examples/` folders when planning code changes

## Architecture

### Two-Phase Execution

1. **Validation** (`releaseIO check`): runs all `validate` functions, no resource acquired, no side effects
2. **Execution** (`releaseIO run`): acquires resource via `Resource.use`, runs validate + execute, threads context

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

GitHub Actions (`ci.yml`) runs: format check, unit tests (sbt 1 + sbt 2 matrix), scripted tests for both modules on both sbt versions. Publishing triggers on `v*` tags via `sbt ci-release`.

## Release Sequence

version bump → tag → push → CI publishes → GitHub release notes
