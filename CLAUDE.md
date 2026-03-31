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
├── core/                               # io.release package
│   ├── src/main/scala/io/release/      # Core plugin sources
│   ├── src/test/scala/                 # Unit tests (MUnit)
│   ├── src/sbt-test/sbt-release-io/    # 50+ scripted integration tests
│   └── examples/                       # Example code
└── monorepo/                           # io.release.monorepo package
    ├── src/main/scala/io/release/monorepo/  # Monorepo extension sources
    ├── src/test/scala/                      # Unit tests
    ├── src/sbt-test/sbt-release-io-monorepo/ # 60+ scripted tests
    └── examples/                            # Example code
docs/
├── core/       # Core plugin documentation
└── monorepo/   # Monorepo plugin documentation
```

## Key Source Files

### Core Module

| File | Purpose |
|------|---------|
| `ReleasePluginIO.scala` | Main sbt plugin (`ReleasePluginIOLike[T]`); auto-triggered; resource lifecycle and two-phase execution |
| `ReleaseStepIO.scala` | Atomic release step with validate/execute phases; builders `fromTask`, `fromInputTask` |
| `ReleaseContext.scala` | Immutable context threaded through steps (versions, vcs, state, metadata) |
| `ReleaseIO.scala` | 60+ setting keys exported by the plugin |
| `ReleaseHookIO.scala` | Hook case class for lifecycle customization |
| `ReleaseHookCompiler.scala` | Compiles policy settings + hooks into ordered step sequence |
| `steps/ReleaseSteps.scala` | 13 default steps (initVcs → checkClean → inquireVersions → tag → publish → push) |
| `vcs/Git.scala` | Git VCS adapter with `IO.blocking` wrappers |

### Monorepo Module

| File | Purpose |
|------|---------|
| `MonorepoReleasePlugin.scala` | Monorepo plugin (`noTrigger`, must be explicitly enabled on root) |
| `MonorepoStepIO.scala` | `Global` and `PerProject` step types |
| `MonorepoContext.scala` | Global context + `ProjectReleaseInfo` per project |
| `ChangeDetection.scala` | Git diff-based change detection with shared-paths support |
| `MonorepoProjectResolver.scala` | Dependency graph resolution and topological sorting |
| `MonorepoSelectionResolver.scala` | Project selection (by name or change detection) |

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

**Supported — Hook lifecycle:** Policy settings enable/disable phases (`releaseIOEnableRunTests`, `releaseIOEnablePublish`, etc.). Hook settings inject logic at semantic points (`releaseIOBeforeTagHooks`, `releaseIOAfterPublishHooks`, etc.). Compiled into ordered step sequence at startup.

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
