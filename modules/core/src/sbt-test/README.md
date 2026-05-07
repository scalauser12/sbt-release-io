# Scripted Tests for sbt-release-io

This directory contains scripted integration tests for the core `releaseIO` command.

Each scenario lives under `sbt-release-io/<test-name>/` and typically contains:

- `build.sbt`
- `version.sbt` or another version file used by the fixture
- `project/plugins.sbt`
- `test`

## Supported coverage

The scripted suite now covers only the supported extension surface:

- policy keys such as `releaseIOPolicyEnable*`, `releaseIOBehaviorSkipPublish`, and `releaseIOVcsIgnoreUntrackedFiles`
- plain lifecycle hooks such as `releaseIOHooksBeforeTag`
- resource-aware hooks via `releaseResourceHooks`

Legacy step-list editing fixtures were retired. New scripted tests should use lifecycle hooks and policy keys only.

## Coverage areas

- Core flow and CLI: `simple`, `help`, `check`, `with-defaults`, `interactive-with-defaults`, `command-line-version-numbers`
- Grouped-key surface and plugin coexistence: `grouped-keys`, `coexisting-plugins-shared-keys`
- Hook lifecycle and policy customization: `hook-lifecycle`, `hook-disabled-phases`, `hook-late-bound-settings`, `custom-plugin-resource-hooks`, `hook-precondition-sees-version`, `hook-precondition-partial-cli-override`
- Hook gating and narrow predicates: `after-publish-gate-streams`, `before-push-hook-gated`, `after-push-hook-gated`, `after-push-hook-runs-on-push`, `hook-installed-publish-action`, `hook-installed-publish-skip`
- ThisBuild scopes and decision defaults: `this-build-overrides`, `thisbuild-decision-default-honored`
- Versioning and tags: `custom-tag`, `custom-version-format`, `global-version-false`, `invalid-version-input`, `version-bump`, `tag-default`, `version-task-sees-snapshot`
- Tag preflight and remote tag probe: `invalid-tag-name-fails-preflight`, `tag-preflight-skipped-with-hook`, `hook-disabled-tag-preflight-still-probes`, `hook-disabled-retry-tag-probes-remote`, `retry-tag-name-probes-remote`, `remote-only-tag-aborts-preflight`
- Cross-build behavior: `cross`, `cross-build-setting`
- Test and clean phases: `run-clean`, `fail-test`, `run-tests-aggregate-fail`, `skip-tests`
- Publish and push flow: `publish-to-check`, `publish-skip`, `publish-skip-root`, `publish-skip-isSnapshot`, `publish-skip-isSnapshot-default-flow`, `publish-runs-after-tag`, `skip-publish-setting`, `publish-multi-project`, `publish-multi-project-manifest`, `publish-nested-aggregate`, `push-changes`, `push-explicit-tag`, `push-first-tracked-branch`, `push-behind-remote`, `push-race-before-push`, `custom-publish-action`
- Push decision and remote warmup: `push-decision-no-skips-remote-warmup`, `push-decision-non-interactive-no-warmup`, `push-disabled-skips-remote-tag-probe`
- Repository hygiene: `modified-files-fail`, `untracked-files`, `untracked-files-fail`, `vcs-signoff`, `commit-message-task-stages-extra`, `commit-rejects-extra-staged`
- Version-file and dependency edge cases: `missing-version-file`, `dash-prefixed-version-file`, `gitignored-version-file`, `untracked-version-file`, `version-file-outside-vcs`, `late-bound-version-file-outside-vcs`, `snapshot-deps`, `snapshot-deps-test-scope`, `snapshot-deps-cross`, `empty-commit`, `empty-commit-noop`
- Documentation packaging: `bundled-runtime-docs`

## Running tests

Run all scripted tests on sbt 1:

```bash
sbt core/scripted
```

Run all scripted tests on sbt 2:

```bash
./bin/sbt2-clean core/scripted
```

Run a specific scenario on sbt 1:

```bash
sbt "core/scripted sbt-release-io/simple"
```

Run a specific scenario on sbt 2:

```bash
./bin/sbt2-clean "core/scripted sbt-release-io/simple"
```

Run multiple scenarios on sbt 1:

```bash
sbt "core/scripted sbt-release-io/simple sbt-release-io/snapshot-deps"
```

Run multiple scenarios on sbt 2:

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
6. **Add the new test name to the relevant bullet under [Coverage areas](#coverage-areas) above.** The list is the only index of what scripted scenarios exist; an unlisted fixture is invisible to anyone scanning the README to find prior coverage of a behavior.

## Debugging tips

- Set `scriptedBufferLog := false` in the fixture `build.sbt` for live output
- Use `$ pause` to inspect fixture state mid-run
- Scripted working directories live under `target/sbt-test/sbt-release-io/<test-name>/`
- Prefer marker files over `target/scala-*` or `target/out/...` paths because sbt 1 and sbt 2 lay out artifacts differently
