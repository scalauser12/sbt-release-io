# Scripted Tests for sbt-release-io

This directory contains scripted integration tests for the core `releaseIO` command.

Each scenario lives under `sbt-release-io/<test-name>/` and typically contains:

- `build.sbt`
- `version.sbt` or another version file used by the fixture
- `project/plugins.sbt`
- `test`

## Supported coverage

The scripted suite now covers only the supported extension surface:

- policy keys such as `releaseIOEnable*`, `releaseIOSkipPublish`, and `releaseIOIgnoreUntrackedFiles`
- plain lifecycle hooks such as `releaseIOBeforeTagHooks`
- resource-aware hooks via `releaseResourceHooks`

Legacy step-list editing fixtures were retired. New scripted tests should use lifecycle hooks and policy keys only.

## Coverage areas

- Core flow and CLI: `simple`, `help`, `check`, `with-defaults`, `interactive-with-defaults`, `command-line-version-numbers`
- Hook and policy customization: `hook-lifecycle`, `hook-disabled-phases`, `hook-late-bound-settings`, `custom-plugin-resource-hooks`
- Versioning and tags: `custom-tag`, `custom-version-format`, `global-version-false`, `invalid-version-input`, `version-bump`, `tag-default`
- Cross-build behavior: `cross`, `cross-build-setting`
- Test and clean phases: `run-clean`, `fail-test`, `run-tests-aggregate-fail`, `skip-tests`
- Publish and push flow: `publish-to-check`, `publish-skip`, `publish-skip-root`, `skip-publish-setting`, `publish-multi-project`, `publish-nested-aggregate`, `push-changes`, `custom-publish-action`
- Repository hygiene: `modified-files-fail`, `untracked-files`, `untracked-files-fail`, `vcs-signoff`
- Version-file and dependency edge cases: `missing-version-file`, `snapshot-deps`, `snapshot-deps-test-scope`, `snapshot-deps-cross`, `empty-commit`, `empty-commit-noop`

## Running tests

Run all scripted tests:

```bash
./bin/sbt2-clean core/scripted
```

Run a specific scenario:

```bash
./bin/sbt2-clean "core/scripted sbt-release-io/simple"
```

Run multiple scenarios:

```bash
./bin/sbt2-clean "core/scripted sbt-release-io/simple sbt-release-io/snapshot-deps"
```

## Script syntax

- `# comment` comments a line
- `> command` runs an sbt command and expects success
- `-> command` runs an sbt command and expects failure
- `$ command` runs a file operation and expects success
- `$- command` runs a file operation and expects failure

Common file operations:

- `$ exists path`
- `$ absent path`
- `$ touch path`
- `$ delete path`
- `$ exec command`
- `$ pause`

## Writing new tests

1. Create `src/sbt-test/sbt-release-io/<test-name>/`
2. Add `build.sbt`, `version.sbt`, `project/plugins.sbt`, and `test`
3. Prefer lifecycle hooks or policy keys over any custom step wiring
4. Use marker files under `baseDirectory.value / "marker"` for assertions that need build-side effects
5. Run `./bin/sbt2-clean "core/scripted sbt-release-io/<test-name>"`

## Debugging tips

- Set `scriptedBufferLog := false` in the fixture `build.sbt` for live output
- Use `$ pause` to inspect fixture state mid-run
- Scripted working directories live under `target/sbt-test/sbt-release-io/<test-name>/`
- Prefer marker files over `target/scala-*` or `target/out/...` paths because sbt 1 and sbt 2 lay out artifacts differently
