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
- Verifies custom format preserved in working directory, git tags, and that `app.name=` lines are not clobbered

### detect-changes-disabled
- `detectChanges=false` setting releases all projects regardless of changes

### detect-changes-excludes
- `releaseIOMonorepoDetectChangesExcludes` filters additional files from change detection
- Excluded-only changes yield "nothing to release"; real changes alongside excluded files still detected

### diamond-dependency
- Diamond dependency graph (base -> left/right, left/right -> top)
- Verifies topological sort puts base first and top last

### dirty-working-dir
- Untracked files abort release in check phase
- Tests `releaseIgnoreUntrackedFiles` behavior

### empty-override-value
- Empty override values (e.g., `release-version core=`) are rejected as parse errors

### first-release-detection
- First release with no prior tags
- Change detection marks all projects as changed, releasing them all

### global-version
- `releaseIOMonorepoUseGlobalVersion := true`
- All projects share a single global `version.sbt` with `ThisBuild / version`

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

### global-version-partial-selection
- Partial project selection in global mode (only core specified)
- Fails because all projects must be included

### interactive-monorepo
- `releaseIOMonorepoInteractive := true` with `with-defaults`
- Verifies interactive mode completes without blocking prompts

### invalid-override
- Typo in project name (e.g., `croe` instead of `core`) causes parse error

### missing-publishto
- Missing `publishTo` configuration causes check phase abort

### next-version-mismatch
- Next version mismatch between projects

### next-version-only
- Only next-version override provided for one project
- Release version is computed; other projects get full computation

### per-project-failure
- One project (core) fails with tag already existing
- Api succeeds due to per-project error isolation; failure propagates to global for post-tag steps

### publish-skip-bypass
- `publish / skip := true` per-project setting bypasses publishTo check for that project only

### push-changes-tracking-remote
- Push-changes uses tracking remote, not `remote.pushDefault`
- Verifies branch and tags pushed to origin even when another remote is the default

### release-version-only
- Only release-version provided (no next-version)
- Next version computed automatically as bugfix bump

### root-project-change-detection
- Root project (baseDir == repo root) uses sbt project ID for tags
- Change detection works for root project separate from subprojects

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

### topological-order
- Projects listed in reverse order (api, middle, core)
- Execution reorders by dependency: core -> middle -> api

### unified-change-detection
- Unified tags with change detection using `v*` pattern
- Releases only projects with changes since the unified tag

### unified-tag-exists
- Pre-existing unified tag causes release failure

### unified-tag-version-mismatch
- Different release versions with unified tag strategy
- Fails because unified tags require matching versions across all projects

### unified-tags
- Single unified tag (`v1.0.0`) for entire monorepo instead of per-project tags
- Tag annotation mentions all released projects

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
> releaseIOMonorepo with-defaults release-version core=1.0.0,api=1.0.0

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
