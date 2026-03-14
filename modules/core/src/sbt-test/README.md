# Scripted Tests for sbt-release-io

This directory contains scripted tests for the sbt-release-io plugin. These tests verify that the plugin works correctly in real-world scenarios.

## Test Structure

Each test is located in `sbt-release-io/<test-name>/` and contains:

- `build.sbt` - Project configuration for the test
- `version.sbt` - Version file that the plugin will modify
- `project/plugins.sbt` - Loads the sbt-release-io plugin
- `test` - Test script with commands to execute

## Available Tests

### check-phase
- Verifies that validation-phase failures prevent step execution

### command-line-version-numbers
- Specifies release and next versions via `release-version` / `next-version` args
- Verifies both versions are written to `version.sbt` at the correct points

### cross
- Tests cross-building with multiple Scala versions via the `cross` flag
- Verifies both 2.13 and 2.12 builds occur only when cross is enabled

### cross-build-setting
- Tests `releaseIOCrossBuild := true` setting (not the CLI flag)
- Verifies both Scala versions are built when cross-build is enabled via setting

### custom-tag
- Tests custom tag naming via the `releaseTagName` setting
- Uses `runtimeVersion` to create dynamic tag names

### custom-version-format
- Tests `releaseVersionFile` (from sbt-release), `releaseIOReadVersion`, and `releaseIOWriteVersion` settings
- Uses a `.properties` file format instead of default `version.sbt`
- Verifies custom format preserved in both working directory and git tag commits

### custom-plugin
- Tests `ReleasePluginIOLike` resource lifecycle (acquire → use → release)
- Verifies marker files prove the resource was acquired, used by a step, and released
- Verifies release side effects: default tag creation and next-version write

### defaults-with-after
- Tests `insertAfter` inserts a custom step at the correct position
- Custom plugin inserts step after `check-clean-working-dir`

### defaults-with-before
- Tests `insertBefore` inserts a custom step at the correct position
- Custom plugin inserts step before `tag-release`

### empty-commit
- Version file already at release version
- Verifies no-op commit is handled gracefully

### empty-commit-noop
- Verifies release flow when version file transitions SNAPSHOT → release → next SNAPSHOT
- Documents commit step behavior when status is empty (no-op)

### exit-code
- Verifies exit codes from `releaseIO` (0 for success, 1 for failure)
- Tests both `fromCommand` and `fromCommandAndRemaining` step factories

### fail-test
- Verifies that failing tests abort the release before later steps execute
- Checks that a marker file is not created when tests fail

### global-version-false
- Tests `releaseUseGlobalVersion := false`
- Verifies `version.sbt` uses `version :=` instead of `ThisBuild / version :=`

### interactive-with-defaults
- Enables `releaseIOInteractive := true`
- Verifies `with-defaults` runs in interactive mode without blocking prompts

### publish-multi-project
- Multi-project build with mixed publish configurations
- Root and libA have `publishTo`, libB uses `publish / skip := true` (no `publishTo` needed)

### publish-skip
- Tests that `publish / skip := true` bypasses the `publishTo` check
- Release succeeds without `publishTo` configured

### publish-to-check
- Verifies that a missing `publishTo` setting fails at check phase
- Verifies the failure happens before any commit, tag, or `version.sbt` mutation

### run-clean
- Verifies the default release flow executes `runClean` before tests
- Asserts files created under `target/` are removed during release

### run-tests-aggregate-fail
- Verifies that failing tests in aggregated sub-projects abort the release
- Multi-project setup with one passing and one failing test

### resource-step-with-check
- Tests `resourceStepWithValidation` in a custom plugin
- Verifies both validate and execute phases run for resource-aware steps

### simple
- Tests the basic release workflow end-to-end
- Verifies version changes, git commits, and tags are created

### skip-publish-setting
- Tests `releaseIOSkipPublish := true` setting (not `publish / skip`)
- Verifies release succeeds without `publishTo` configured when skip-publish is enabled via setting

### skip-tests
- Verifies that the `skip-tests` flag allows release despite failing tests
- Tests both the negative case (failure) and positive case (success with flag)

### step-command-and-remaining
- Tests `stepCommandAndRemaining` factory in `releaseIOProcess`
- Verifies command execution and drain logic

### snapshot-deps
- Project with a SNAPSHOT dependency
- Verifies release fails with appropriate error before any commit, tag, or `version.sbt` mutation

### snapshot-deps-cross
- Cross-build project where only one Scala version has a SNAPSHOT dependency
- Verifies `cross` release fails when any version has snapshot dependencies before any commit, tag, or `version.sbt` mutation

### tag-default
- Tests tag name conflict handling with multiple scenarios
- Covers abort, overwrite, keep, and custom tag name resolution

### tasks-as-steps
- Tests `stepTask`, `stepTaskAggregated`, `stepInputTask`, and `stepCommand` factories
- Verifies tasks, input tasks, and commands all execute correctly as release steps

### untracked-files
- Tests `releaseIgnoreUntrackedFiles := true`
- Creates untracked files and verifies release succeeds when the setting is enabled

### untracked-files-fail
- Tests default `releaseIgnoreUntrackedFiles` behavior (false)
- Verifies untracked files block the release before any commit, tag, or `version.sbt` mutation

### version-bump
- Tests different version bump strategies: Next, NextStable, Bugfix, Minor
- Verifies qualifier stripping and snapshot suffix behavior for each

### with-defaults
- Tests release with and without the `with-defaults` flag
- Verifies `releaseVersionBump` setting is honored across multiple scenarios

## Running Tests

Run all tests:
```bash
sbt scripted
```

Run a specific test:
```bash
sbt "scripted sbt-release-io/simple"
```

Run multiple specific tests:
```bash
sbt "scripted sbt-release-io/simple sbt-release-io/snapshot-deps"
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

# Run release
> releaseIO with-defaults release-version 0.1.0

# Verify results
$ exists version.sbt
$ exec git tag | grep -q "v0.1.0"
```

## Writing New Tests

1. Create directory: `src/sbt-test/sbt-release-io/<test-name>/`
2. Add `build.sbt`, `version.sbt`, `project/plugins.sbt`
3. Write test script in `test` file
4. Run with `sbt "scripted sbt-release-io/<test-name>"`

## Debugging Tests

- Set `scriptedBufferLog := false` in build.sbt to see real-time output
- Use `$ pause` in test scripts to inspect state
- Test directories are in `target/sbt-test/sbt-release-io/<test-name>/`
- Prefer explicit marker files under `baseDirectory.value / "marker"` over build output paths, since sbt 1 and sbt 2 lay out compiled artifacts differently

## Tips

- Tests run in isolated temporary directories
- Each test starts with a clean slate
- Git must be configured (`user.email`, `user.name`) in tests
- Plugin is published locally before tests run
- Use marker files for scripted assertions instead of `target/scala-*` or `target/out/...` paths
- Tests can be slow (each is a full sbt session)
