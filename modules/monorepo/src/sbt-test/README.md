# Scripted Tests for sbt-release-io-monorepo

This directory contains scripted tests for the sbt-release-io-monorepo plugin. These tests verify that the monorepo release plugin works correctly in real-world scenarios.

## Test Structure

Each test is located in `sbt-release-io-monorepo/<test-name>/` and contains:

- `build.sbt` - Multi-project build configuration for the test
- `version.sbt` (or per-project version files) - Version files that the plugin will modify
- `project/plugins.sbt` - Loads the sbt-release-io-monorepo plugin
- `test` - Test script with commands to execute

## Available Tests

### all-changed
- `all-changed` CLI flag bypasses change detection and releases all projects
- Creates tags for both projects even when only one has actual changes

### change-detection
- Tests default behavior without explicit project selection
- Only detects and releases projects with changes since their last tag

### change-detection-downstream
- Tests `releaseIOMonorepoIncludeDownstream := true` with a three-project chain (core <- api <- web)
- Only core has file changes; api and web are included as transitive downstream dependents
- Verifies all three projects are released with correct versions and tags

### cli-all-changed-with-selection
- Cannot combine `all-changed` flag with explicit project selection
- Rejects the combination at CLI parse time

### cli-multiple-global-release-version
- Rejects multiple global release-version values
- Only one global release-version override is allowed

### cli-override-forces-detection
- Version overrides force-include unchanged projects in change-detection mode
- Only core has changes but both core and api have version overrides
- Verifies both projects are released

### cli-unused-overrides
- Version overrides for non-selected projects are rejected
- Selects only core but provides overrides for both core and api

### cli-parse-errors
- Validates CLI argument format and rejects duplicate per-project overrides
- Rejects malformed arguments: missing values, invalid format, empty values, typos in project names
- Detects duplicate per-project `release-version` or `next-version` overrides for the same project

### cross-build-empty-cross
- Cross-building fails when a project has empty `crossScalaVersions`
- Core runs successfully but api fails; downstream steps skipped

### cross-build-heterogeneous
- Cross-building with different Scala versions per project
- Core built for multiple versions, api for a single version

### cross-build-setting
- Tests `releaseIOMonorepoCrossBuild := true` setting (not the `cross` CLI flag)
- Verifies cross-building activates via build setting with heterogeneous Scala versions

### custom-change-detector
- Custom change detection function selects only specific projects
- Returns true only for core, skipping api

### custom-detector-error
- Custom change detector throws for one project
- Project conservatively treated as changed and released anyway

### custom-detector-uses-basedir
- Custom change detector uses the `baseDir` parameter to run `git diff HEAD~1`
- Filters out `version.sbt` from diff results; only releases projects with real file changes

### custom-plugin
- Tests `MonorepoReleasePluginLike` resource lifecycle (acquire -> use -> release)
- Verifies marker files prove the resource was acquired, used by a step, and released

### custom-projects-setting
- Overrides `releaseIOMonorepoProjects` to a subset of aggregated projects
- Three projects (core, api, extra); only core and api released; extra unchanged

### custom-tag-name
- Custom per-project tag name formatter
- Creates tags with format `release/<project>/<version>`

### custom-unified-tag-name
- Tests `releaseIOMonorepoUnifiedTagName` custom formatter with unified tag strategy
- Produces `release-v1.0.0` instead of default `v1.0.0`
- Verifies tag annotation mentions all released projects

### custom-version-format
- Tests `releaseIOMonorepoVersionFile`, `releaseIOMonorepoReadVersion`, and `releaseIOMonorepoWriteVersion`
- Uses `.properties` files instead of default `version.sbt` format
- Uses the state-aware `releaseIOMonorepoVersionFile` resolver signature
- Verifies custom format preserved in working directory, git tags, and that `app.name=` lines are not clobbered

### detect-changes-disabled
- `releaseIOMonorepoDetectChanges := false` releases all projects regardless of changes

### detect-changes-excludes
- `releaseIOMonorepoDetectChangesExcludes` filters additional files from change detection
- Excluded-only changes yield "nothing to release"; real changes alongside excluded files still detected

### diamond-dependency
- Diamond dependency graph (base -> left/right, left/right -> top)
- Verifies topological sort puts base first and top last

### dirty-working-dir
- Untracked files abort release in check phase
- Tests `releaseIOIgnoreUntrackedFiles` behavior
- Verifies failure happens before any commit, tag, or version-file mutation

### empty-commit-monorepo
- `commitIfChanged` skips commit when version files already contain the release version
- Pre-commits release versions so `set-release-versions` sees no diff; verifies no extra commit is created

### empty-override-value
- Empty override values (e.g., `release-version core=`) are rejected as parse errors

### first-release-detection
- First release with no prior tags
- Change detection marks all projects as changed, releasing them all

### global-version
- `releaseIOMonorepoUseGlobalVersion := true`
- All projects share a single global `version.sbt` with `ThisBuild / version`

### global-version-custom-write
- Custom `releaseIOMonorepoReadVersion` and `releaseIOMonorepoWriteVersion` with `releaseIOMonorepoUseGlobalVersion := true`
- Uses `.properties` file format with a shared root-level version file
- Verifies properties format preserved (`app.name=` not clobbered) and correct versions in tags

### global-version-change-detection-subset
- Change detection finds only a subset of projects changed in global mode
- Fails because global mode enforces all-or-nothing

### global-version-format
- Verifies global version file uses `ThisBuild / version` format
- Not plain `version :=`

### global-version-override
- Tests global version override syntax (`release-version 2.0.0` without `project=`)
- Applies the same version to all projects in global version mode
- Verifies per-project tags and next version in `version.sbt`

### global-version-mismatch
- Per-project version overrides provided in global mode (e.g., `release-version core=1.0.0`)
- Release fails at argument validation — per-project overrides are not supported in global mode

### global-version-task-mismatch
- Custom `releaseVersion` tasks produce different values per project in global mode
- Release fails at `validate-versions` step before `setReleaseVersions` mutates the shared `version.sbt`
- Verifies the version file and git state remain untouched after failure

### global-version-partial-selection
- Partial project selection in global mode (only core specified)
- Fails because all projects must be included

### interactive-monorepo
- `releaseIOMonorepoInteractive := true` with `with-defaults`
- Verifies interactive mode completes without blocking prompts

### interactive-with-defaults-tag
- `interactive=true` + `with-defaults` exercises the `useDefaults` tag collision path
- Pre-existing tag causes abort with "use-defaults mode" message, not the non-interactive message

### invalid-override
- Typo in project name (e.g., `croe` instead of `core`) causes parse error

### late-bound-detect-settings
- Late-bound change detection settings via a custom plugin (`LateBoundDetectPlugin`)
- Verifies detect settings are resolved at release time, not at plugin load time

### late-bound-projects-setting
- Late-bound `releaseIOMonorepoProjects` via a custom plugin (`LateBoundProjectsPlugin`)
- Verifies only the late-resolved subset of projects (core only) is released

### late-bound-version-settings
- Late-bound `releaseIOMonorepoVersionFile`, `releaseIOMonorepoReadVersion`, and `releaseIOMonorepoWriteVersion` via a custom plugin
- Uses `.properties` format resolved at release time; verifies the original `version.sbt` stays unchanged

### missing-publishto
- Missing `publishTo` configuration causes check phase abort

### missing-version-file
- Missing `version.sbt` for a project fails in check phase with a clear error
- Release aborted before any commits or tags

### nested-parent-exclusion
- Changes only in a nested child directory (e.g. `services/api/`) do NOT falsely mark the parent (`services`) as changed
- Tests the generalized child-directory exclusion for non-root parent projects in a 3-level hierarchy

### next-version-mismatch
- Per-project next-version overrides rejected in global-version mode
- Similar to `global-version-mismatch` but specifically for `next-version` overrides

### next-version-only
- Only next-version override provided for one project
- Release version is computed; other projects get full computation

### per-project-failure
- One project (core) fails during `run-tests` with a custom task exception
- Failure propagates globally before any version/tag steps run
- Verifies commit count, tags, and project version files remain untouched after failure

### per-project-releaseversionfile
- One project overrides upstream sbt-release `releaseVersionFile` without overriding `releaseIOMonorepoVersionFile`
- Verifies the monorepo plugin reads, writes, commits, and tags the overridden file path correctly
- Mixed formats are preserved: `core/version.properties` and `api/version.sbt`

### publish-artifacts-checks-disabled
- `releaseIOMonorepoPublishArtifactsChecks := false` bypasses `publishTo` validation
- Release succeeds despite missing `publishTo` configuration

### publish-skip-bypass
- `publish / skip := true` per-project setting bypasses publishTo check for that project only

### publish-skip-eval-error
- `publish / skip` throws during publish preflight evaluation
- Release fails with the wrapped project-scoped error message and preserves the original cause text

### publish-to-eval-error
- `publishTo` throws during publish preflight evaluation
- Release fails with the wrapped project-scoped error message and preserves the original cause text

### push-behind-remote
- `pushChanges` validate fails when local branch is behind remote
- Two-phase compose model ensures no version modifications occur when validate fails

### push-changes-tracking-remote
- Push-changes uses tracking remote, not `remote.pushDefault`
- Verifies branch and tags pushed to origin even when another remote is the default

### release-version-only
- Only release-version provided (no next-version)
- Next version computed automatically as bugfix bump

### run-clean
- Releases only the explicitly selected project in a two-project build
- Verifies `run-clean` removes generated output for the released project but leaves the non-selected project's target output intact

### selection-aware-validation
- Unselected projects are not validated after explicit project selection
- Selects only core (which has `publishTo`); api (missing `publishTo`) is skipped without error

### shared-paths-detection
- A change in `build.sbt` (shared path) triggers release for all projects
- No per-project file changes needed; shared path change alone is sufficient

### shared-paths-disabled
- `releaseIOMonorepoSharedPaths := Seq.empty` disables shared path detection
- Shared-path-only changes yield "No projects have changed" when detection is disabled

### root-project-change-detection
- Root project (baseDir == repo root) uses sbt project ID for tags
- Change detection works for root project separate from subprojects

### root-project-sibling-exclusion
- Changes only in a child project directory do NOT falsely mark the root project as changed
- Root project (diff scope `.`) excludes sibling project directories from its diff results

### simple-monorepo
- Basic per-project release with two projects (core, api)
- Verifies version files updated, git commits created, and per-project tags generated

### skip-publish
- `skipPublish=true` setting bypasses publishTo check in both check and publish phases

### skip-tests
- `skip-tests` CLI flag skips test execution but release still completes

### skip-tests-setting
- Tests `releaseIOMonorepoSkipTests := true` setting (not the `skip-tests` CLI flag)
- Marker-file approach proves tests were not executed despite `Test / test` being overridden to write markers

### snapshot-dependencies
- SNAPSHOT dependencies detected in check phase
- Release aborted before any commits or tags

### tag-exists-error
- Pre-existing per-project tag causes per-project failure with isolation
- Other projects succeed but next-version step skipped globally

### tag-exists-noninteractive
- Per-project tag collision in non-interactive mode (no `with-defaults`)
- Exercises the `!ctx.interactive` path in `createTag`; api succeeds via isolation

### transitive-aggregates
- 3-level aggregate hierarchy: root -> services -> api
- Default `releaseIOMonorepoProjects` transitively discovers nested aggregates
- Verifies both `services` and `api` are released without manual override
- Verifies built-in `run-tests` and `publish-artifacts` steps stay project-scoped and do not re-run `api`

### topological-order
- Projects listed in reverse order (api, middle, core)
- Execution reorders by dependency: core -> middle -> api

### unified-change-detection
- Unified tags with change detection using `v*` pattern
- Releases only projects with changes since the unified tag

### unified-tag-exists
- Pre-existing unified tag causes release failure
- Verifies the pre-existing tag is preserved, release-version commit is allowed, and next-version writes are skipped

### unified-tag-noninteractive
- Unified tag collision in non-interactive mode (no `with-defaults`)
- Exercises the `!ctx.interactive` path for unified tags

### unified-tag-version-mismatch
- Different release versions with unified tag strategy
- Fails because unified tags require matching versions across all projects
- Verifies validation failure leaves tags and version files untouched

### unified-tags
- Single unified tag (`v1.0.0`) for entire monorepo instead of per-project tags
- Tag annotation mentions all released projects

### vcs-signoff
- `releaseIOVcsSignOff := true` propagates to `git commit -s`
- Verifies release commits include the `Signed-off-by:` trailer

### version-file-change-detection
- Version-only file changes are filtered out from change detection
- Release aborts with "Nothing to release" when only version files changed

### zero-changed-projects
- No changes detected after tagging, no explicit project selection
- Release aborts with zero changed projects

## Running Tests

Run all monorepo tests:
```bash
sbt monorepo/scripted
```

Run a specific test:
```bash
sbt "monorepo/scripted sbt-release-io-monorepo/simple-monorepo"
```

Run multiple specific tests:
```bash
sbt "monorepo/scripted sbt-release-io-monorepo/simple-monorepo sbt-release-io-monorepo/change-detection"
```

## Test Script Syntax

### Commands

- `#` - Comment
- `> task` - Run sbt task, expect success
- `-> task` - Run sbt task, expect failure
- `$ command` - Run file operation, expect success
- `$- command` - Run file operation, expect failure

### Common File Operations

- `$ exists path/to/file` - Verify file exists
- `$ absent path/to/file` - Verify file doesn't exist
- `$ touch path/to/file` - Create or update file
- `$ delete path/to/file` - Delete file
- `$ exec command` - Execute shell command
- `$ pause` - Pause test for debugging (press Enter to continue)

### Example Test Script

```
# Initialize git
$ exec git init
$ exec git config user.email "test@example.com"
$ exec git config user.name "Test User"
$ exec git add .
$ exec git commit -m "Initial"

# Run monorepo release
> releaseIOMonorepo with-defaults release-version core=1.0.0 next-version core=1.1.0-SNAPSHOT release-version api=1.0.0 next-version api=1.1.0-SNAPSHOT

# Verify per-project tags
$ exec git tag | grep -q "core/v1.0.0"
$ exec git tag | grep -q "api/v1.0.0"
```

## Writing New Tests

1. Create directory: `src/sbt-test/sbt-release-io-monorepo/<test-name>/`
2. Add `build.sbt` (multi-project), version files, `project/plugins.sbt`
3. Write test script in `test` file
4. Run with `sbt "monorepo/scripted sbt-release-io-monorepo/<test-name>"`

## Debugging Tests

- Set `scriptedBufferLog := false` in build.sbt to see real-time output
- Use `$ pause` in test scripts to inspect state
- Test directories are in `target/sbt-test/sbt-release-io-monorepo/<test-name>/`
- Look for `target/` directories within test projects for build artifacts

## Tips

- Tests run in isolated temporary directories
- Each test starts with a clean slate
- Git must be configured (`user.email`, `user.name`) in tests
- Plugin is published locally before tests run
- Tests can be slow (each is a full sbt session)
- Monorepo tests typically need at least two subprojects to be meaningful
