# Scripted Tests for sbt-release-io-monorepo

This directory contains scripted integration tests for the per-project monorepo `releaseIOMonorepo` command.

Each scenario lives under `sbt-release-io-monorepo/<test-name>/` and typically contains:

- `build.sbt`
- per-project `version.sbt` files or another per-project version format
- `project/plugins.sbt`
- `test`

## Supported coverage

The monorepo scripted suite covers only the supported customization surface:

- policy keys such as `releaseIOMonorepoEnable*`, `releaseIOMonorepoSkipPublish`, and `releaseIOIgnoreUntrackedFiles`
- global and per-project lifecycle hooks
- resource-aware hooks via `monorepoResourceHooks`

Legacy process-editing fixtures are no longer part of the scripted suite.

## Coverage areas

- Core flow and CLI: `simple-monorepo`, `help`, `check`, `interactive-monorepo`, `release-version-only`, `next-version-only`, `cli-*`, `invalid-override`, `empty-override-value`, `keyword-project-selector`
- Hook and policy customization: `hook-lifecycle`, `hook-disabled-phases`, `hook-late-bound-settings`, `custom-plugin-resource-hooks`, `custom-projects-setting`
- Project selection and change detection: `change-detection`, `all-changed`, `change-detection-downstream`, `cli-all-changed-with-selection`, `cli-unused-overrides`, `detect-changes-disabled`, `detect-changes-excludes`, `first-release-detection`, `shared-paths-*`, `root-project-*`, `zero-changed-projects`
- Change-detection extensions: `custom-change-detector`, `custom-detector-error`, `custom-detector-uses-basedir`
- Version files and tags: `custom-version-format`, `per-project-releaseversionfile`, `version-file-change-detection`, `custom-tag-name`, `tag-exists-error`
- Dependency ordering and cross-build: `diamond-dependency`, `topological-order`, `transitive-aggregates`, `nested-parent-exclusion`, `cross-build-setting`, `cross-build-heterogeneous`, `cross-build-empty-cross`, `cross-build-restore`
- Publish, push, and validation flow: `manifest-metadata`, `missing-publishto`, `missing-version-file`, `snapshot-dependencies`, `publish-artifacts-checks-disabled`, `publish-skip-bypass`, `publish-skip-eval-error`, `publish-to-eval-error`, `push-behind-remote`, `push-changes-tracking-remote`, `selection-aware-validation`, `skip-publish`, `skip-tests`, `skip-tests-setting`, `vcs-signoff`
- Project test and failure handling: `per-project-failure`, `dirty-working-dir`, `empty-commit-monorepo`, `run-clean`

## Migration note

Global version mode and unified tag strategy were removed. The surviving suite covers only per-project version files, per-project `project=version` CLI overrides, and per-project tags.

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
