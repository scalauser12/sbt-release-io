# Scripted Tests for sbt-release-io-monorepo

This directory contains scripted integration tests for the per-project monorepo `releaseIOMonorepo` command.

Each scenario lives under `sbt-release-io-monorepo/<test-name>/` and typically contains:

- `build.sbt`
- per-project `version.sbt` files or another per-project version format
- `project/plugins.sbt`
- `test`

## Supported coverage

The monorepo scripted suite covers only the supported customization surface:

- policy keys such as `releaseIOMonorepoPolicyEnable*`, `releaseIOMonorepoBehaviorSkipPublish`, and `releaseIOVcsIgnoreUntrackedFiles`
- global and per-project lifecycle hooks
- resource-aware hooks via `monorepoResourceHooks`

Legacy process-editing fixtures are no longer part of the scripted suite.

## Coverage areas

- Core flow and CLI: `simple-monorepo`, `help`, `check`, `interactive-monorepo`, `release-version-only`, `next-version-only`, `cli-all-changed-with-selection`, `cli-override-forces-detection`, `cli-parse-errors`, `cli-unused-overrides`, `invalid-override`, `empty-override-value`, `keyword-project-selector`
- Hook lifecycle and policy customization: `hook-lifecycle`, `hook-disabled-phases`, `hook-late-bound-settings`, `hook-late-bound-settings-legacy-append`, `hook-late-bound-settings-legacy-before-commit`, `hook-late-bound-settings-legacy-before-tag`, `custom-plugin-resource-hooks`, `custom-projects-setting`, `grouped-keys`, `project-scala-shared-import`, `hook-precondition-sees-version`, `hook-precondition-no-cli-override`
- ThisBuild scopes: `thisbuild-overrides`
- Hook gating and narrow predicates: `before-publish-hook-narrowed`, `before-push-hook-gated`, `after-push-hook-gated`, `hook-installed-publish-action`, `hook-installed-publish-skip`
- Project selection and change detection: `change-detection`, `all-changed`, `change-detection-downstream`, `detect-changes-disabled`, `detect-changes-excludes`, `first-release-detection`, `shared-paths-custom`, `shared-paths-detection`, `shared-paths-disabled`, `root-project-change-detection`, `root-project-sibling-exclusion`, `zero-changed-projects`, `no-changes-without-upstream`
- Change-detection extensions: `custom-change-detector`, `custom-detector-error`, `custom-detector-uses-basedir`
- Version files and tags: `custom-version-format`, `per-project-releaseversionfile`, `version-file-change-detection`, `shared-version-file`, `custom-tag-name`, `tag-exists-error`, `gitignored-version-file`
- Tag preflight and remote tag probe: `invalid-tag-name-fails-preflight`, `hook-disabled-tag-preflight-still-probes`, `retry-tag-name-probes-remote`, `overwrite-answer-probes-remote`, `remote-only-tag-aborts-preflight`
- Dependency ordering and cross-build: `diamond-dependency`, `topological-order`, `transitive-aggregates`, `nested-parent-exclusion`, `cross-build-setting`, `cross-build-heterogeneous`, `cross-build-empty-cross`, `cross-build-restore`, `cross-build-with-deps`
- Publish, push, and validation flow: `manifest-metadata`, `missing-publishto`, `missing-version-file`, `snapshot-dependencies`, `publish-artifacts-checks-disabled`, `publish-runs-after-tag`, `publish-skip-bypass`, `publish-skip-isSnapshot`, `publish-skip-isSnapshot-default-flow`, `publish-skip-eval-error`, `publish-to-eval-error`, `push-behind-remote`, `push-changes-tracking-remote`, `push-decision-no-skips-remote-warmup`, `selection-aware-validation`, `skip-publish`, `skip-tests`, `skip-tests-setting`, `vcs-signoff`
- Project test and failure handling: `per-project-failure`, `dirty-working-dir`, `empty-commit-monorepo`, `run-clean`

## Migration note

Global version mode and unified tag strategy were removed. The normal supported path still uses per-project version files, per-project `project=version` CLI overrides, and per-project tags. The `shared-version-file` fixture remains as negative coverage to guard against stale shared/global-version-file configuration.

## Running tests

Run the monorepo scripted suite on sbt 1:

```bash
sbt monorepo/scripted
```

Run the monorepo scripted suite on sbt 2:

```bash
./bin/sbt2-clean monorepo/scripted
```

Run a specific scenario on sbt 1:

```bash
sbt "monorepo/scripted sbt-release-io-monorepo/simple-monorepo"
```

Run a specific scenario on sbt 2:

```bash
./bin/sbt2-clean "monorepo/scripted sbt-release-io-monorepo/simple-monorepo"
```

Run multiple scenarios on sbt 1:

```bash
sbt "monorepo/scripted sbt-release-io-monorepo/simple-monorepo sbt-release-io-monorepo/tag-exists-error"
```

Run multiple scenarios on sbt 2:

```bash
./bin/sbt2-clean "monorepo/scripted sbt-release-io-monorepo/simple-monorepo sbt-release-io-monorepo/tag-exists-error"
```

## Writing new tests

1. Create `src/sbt-test/sbt-release-io-monorepo/<test-name>/`
2. Add `build.sbt`, per-project `version.sbt` files (or another per-project version format), `project/plugins.sbt`, and `test`
3. Prefer global / per-project lifecycle hooks or policy keys over any custom step wiring
4. Add `target/`, `project/target/`, `project/project/`, and `global/` to the fixture's `.gitignore` so sbt's scripted boot directory is not staged when the `test` script uses `git add .`
5. Run `./bin/sbt2-clean "monorepo/scripted sbt-release-io-monorepo/<test-name>"`
6. **Add the new test name to the relevant bullet under [Coverage areas](#coverage-areas) above.** The list is the only index of what scripted scenarios exist; an unlisted fixture is invisible to anyone scanning the README to find prior coverage of a behavior.
