# Contributing

Read [ARCHITECTURE.md](ARCHITECTURE.md) for module boundaries and how a release
command flows through the runtime before changing core or monorepo internals.

## Build & Test

The plugin cross-builds against sbt 1 (Scala 2.12) and sbt 2 (Scala 3). Code
that touches runtime behavior, command execution, or the release flow must
verify on both lanes.

### sbt 1 (Scala 2.12, default)

```bash
sbt compile              # compile all modules
sbt test                 # run unit tests (MUnit)
sbt core/test            # core unit tests only
sbt monorepo/test        # monorepo unit tests only
sbt scripted             # run every scripted integration test
sbt "core/scripted sbt-release-io/<test-name>"          # one core scripted test
sbt "monorepo/scripted sbt-release-io-monorepo/<test-name>"  # one monorepo scripted test
```

### sbt 2 (Scala 3)

Prefer the helper script for local checkouts so generated IDE files
(`project/metals.sbt`, `.bloop/`) don't interfere:

```bash
./bin/sbt2-clean compile
./bin/sbt2-clean test
./bin/sbt2-clean core/scripted
./bin/sbt2-clean monorepo/scripted
```

Plain `sbt -Dsbt.version=2.0.0-RC9 ...` works from a clean checkout / CI.

## Formatting

```bash
sbt scalafmtAll          # format Scala sources
sbt scalafmtSbt          # format .sbt and project/*.scala build files
sbt scalafmtCheckAll     # verify Scala source formatting
sbt scalafmtSbtCheck     # verify sbt/build file formatting
```

CI fails on unformatted code. Run `scalafmtAll` and `scalafmtSbt` before
opening a PR.

## Scripted tests

Each plugin module ships a large set of scripted integration tests. Layout:

- Core: [modules/core/src/sbt-test/](../modules/core/src/sbt-test/) — see
  [its README](../modules/core/src/sbt-test/README.md).
- Monorepo: [modules/monorepo/src/sbt-test/](../modules/monorepo/src/sbt-test/)
  — see [its README](../modules/monorepo/src/sbt-test/README.md).

A scripted test is a `build.sbt` plus a `test` script that drives the plugin
end-to-end against a temp git repo. Add a new test for any change that affects
release flow, command output, hook lifecycle, or version/tag behavior. Unit
tests verify code; scripted tests verify behavior.

## Pull requests

Branch off `main`. Before opening a PR, run:

1. `sbt scalafmtCheckAll scalafmtSbtCheck`
2. `sbt test` and `./bin/sbt2-clean test`
3. Targeted scripted tests for the area you touched (full `scripted` is
   slow — let CI cover the long pole).

Keep changes narrow by module boundary: `modules/core` for single-project
behavior, `modules/monorepo` for monorepo-specific behavior, `modules/runtime`
for shared internals. Public API removals or behavior changes need a
[CHANGELOG.md](../CHANGELOG.md) entry with a migration note.

CI is defined in
[.github/workflows/ci.yml](../.github/workflows/ci.yml) and runs format
checks, unit tests on both sbt lanes, scripted tests for both plugins on both
sbt lanes, and a publish-local smoke. Releases ship from pushed `v*` tags via
GitHub Actions — don't run `sbt ci-release` or modify release credentials
manually.
