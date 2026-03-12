# sbt-release-io

A cats-effect IO release plugin for sbt, inspired by sbt-release. Two modules:
- **core** (`sbt-release-io`): main plugin in `modules/core/src/main/scala/io/release/`
- **monorepo** (`sbt-release-io-monorepo`): monorepo extension in `modules/monorepo/src/main/scala/io/release/monorepo/`

Scala 2.12 with `-Xsource:3`. sbt 1.12.3. cats-effect 3.6.3. specs2 for tests.

## Build & Test Commands

- `sbt compile` — compile both modules
- `sbt test` — run unit tests (specs2) for both modules
- `sbt scripted` — run all scripted integration tests (~28 tests, takes ~3 min)
- `sbt core/test` — run core unit tests only
- `sbt monorepo/test` — run monorepo unit tests only
- `sbt scalafmtAll` — format Scala source files
- `sbt scalafmtSbt` — format `.sbt` and build definition files
- `sbt scalafmtCheckAll` — verify Scala source formatting
- `sbt scalafmtSbtCheck` — verify `.sbt` and build definition formatting

## Coding Conventions

- Scala 2.12 with `-Xsource:3` — `import foo.{*, given}` and `[?]` wildcards are valid
- Formatting: scalafmt 3.10.7 with `runner.dialect = scala212source3`, `project.layout = StandardConvention`, `lang:scala-3 = scala3`, and `.sbt = sbt1`
- Use cats-effect `IO` for all effectful operations; wrap blocking calls in `IO.blocking`
- Error handling: use `scala.util.control.NonFatal` in catch blocks, never bare `RuntimeException`
- Use `handleErrorWith` for per-project error isolation in monorepo steps
- Prefer `_root_.io.release.X` when `import sbt.*` shadows the `io` package
- sbt plugin helpers live in `steps/` subpackages; shared utilities go in `StepHelpers` / `MonorepoStepHelpers`
- Test framework: specs2 mutable specifications
- Scripted tests use `baseDirectory.value / "marker"` (not `target.value`) for marker files

## Compaction Instructions

When compacting this conversation, preserve the following:

1. **What the user asked for** — the original request and any clarifications, verbatim or close to it
2. **Which files were modified** — full paths and a one-line summary of each change
3. **Which files were read but not modified** — so they don't need to be re-read
4. **Current task state** — what's done, what's in progress, what's pending
5. **Errors encountered and how they were resolved** — especially:
   - `import sbt.*` shadowing `io.release` → use `_root_.io.release`
   - `releaseVersionFile` not in scope → use `sbtrelease.ReleasePlugin.autoImport.releaseVersionFile`
   - sbt `LoadCompat.reapply` vs `Load.reapply` differences
6. **Test results** — last known pass/fail counts for `sbt test` and `sbt scripted`
7. **The active plan file path** if plan mode was used
8. **Key architectural decisions made** — e.g., two-phase compose (checks then actions), failureCheck interleaving, per-project failure propagation to global context

Do NOT preserve:
- Full file contents that were merely read for context
- Redundant intermediate states of files that were edited multiple times
- Tool call details / raw output beyond what's needed to understand the change
