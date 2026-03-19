# sbt-release-io

Scala/sbt plugin porting sbt-release to cats-effect IO. Two modules: **core** (single-project releases) and **monorepo** (multi-project releases with change detection).

## Build & Test

```bash
sbt compile                # compile all modules
sbt test                   # run all unit tests (specs2)
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
```

### Formatting

```bash
sbt scalafmtAll            # format Scala sources
sbt scalafmtSbt            # format .sbt and project/*.scala files
sbt scalafmtCheckAll       # check Scala source formatting
sbt scalafmtSbtCheck       # check sbt/build file formatting
```

## Conventions

- Scala 2.12 (sbt 1) and Scala 3 (sbt 2) cross-build
- Max line length: 100 columns (enforced by scalafmt 3.10.7)
- Tests use specs2-core with cats-effect-testing-specs2
- Scripted tests live under `src/sbt-test/` in each module
- Core sources: `modules/core/src/main/scala/io/release/`
- Monorepo sources: `modules/monorepo/src/main/scala/io/release/monorepo/`

## CI

GitHub Actions runs: format check, unit tests (sbt 1 + sbt 2 matrix), scripted tests for both modules on both sbt versions.
