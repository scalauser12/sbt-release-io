# Scripted Tests for sbt-release-io-monorepo

This directory contains scripted integration tests for the per-project monorepo release model.

Each scenario lives under `sbt-release-io-monorepo/<test-name>/` and typically contains:

- `build.sbt`
- per-project `version.sbt` files (or a custom per-project version-file format)
- `project/plugins.sbt`
- `test`

## Coverage areas

- Project selection: `change-detection`, `all-changed`, `cli-all-changed-with-selection`, `cli-unused-overrides`, `keyword-project-selector`, `zero-changed-projects`
- Change-detection extensions: `change-detection-downstream`, `custom-change-detector`, `custom-detector-error`, `custom-detector-uses-basedir`, `detect-changes-disabled`, `detect-changes-excludes`, `shared-paths-*`
- CLI and help: `help`, `check`, `check-without-builtins`, `cli-override-forces-detection`, `cli-parse-errors`, `empty-override-value`, `invalid-override`, `keyword-project-selector`
- Version files and custom formats: `custom-version-format`, `late-bound-version-settings`, `per-project-releaseversionfile`, `version-file-change-detection`
- Tagging: `custom-tag-name`, `tag-exists-error`
- Dependency ordering and aggregation: `diamond-dependency`, `topological-order`, `transitive-aggregates`, `nested-parent-exclusion`, `root-project-*`
- Cross-build behavior: `cross-build-setting`, `cross-build-heterogeneous`, `cross-build-empty-cross`, `cross-build-restore`
- Publish and push flow: `missing-publishto`, `publish-*`, `push-*`, `skip-publish`, `skip-tests`, `vcs-signoff`
- Custom plugin and step composition: `custom-plugin`, `custom-projects-setting`, `insert-step`, `late-bound-*`

## Migration note

Global version mode and unified tag strategy were removed. The scripted suite now covers only:

- per-project version files
- per-project `project=version` CLI overrides
- per-project tags

## Coverage after removal

The surviving scripted suite still covers the remaining monorepo surface in grouped scenario families:

- per-project tagging: `custom-tag-name`, `tag-exists-error`, `interactive-monorepo`
- per-project version files and formats: `per-project-releaseversionfile`, `custom-version-format`, `version-file-change-detection`
- per-project change detection: `change-detection`, `shared-paths-*`, `first-release-detection`, `root-project-*`
- CLI/help/check behavior: `help`, `check`, `check-without-builtins`, `cli-*`, `invalid-override`, `empty-override-value`, `keyword-project-selector`
