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
- Verifies that check-phase failures prevent action execution

### command-line-version-numbers
- Specifies release and next versions via `release-version` / `next-version` args
- Verifies both versions are written to `version.sbt` at the correct points

### cross
- Tests cross-building with multiple Scala versions via the `cross` flag
- Verifies both 2.13 and 2.12 builds occur only when cross is enabled

### custom-tag
- Tests custom tag naming via the `releaseTagName` setting
- Uses `runtimeVersion` to create dynamic tag names

### empty-commit
- Version file already at release version
- Verifies no-op commit is handled gracefully

### exit-code
- Verifies exit codes from `releaseIO` (0 for success, 1 for failure)
- Tests both `fromCommand` and `fromCommandAndRemaining` step factories

### fail-test
- Verifies that failing tests abort the release before later steps execute
- Checks that a marker file is not created when tests fail

### publish-to-check
- Verifies that a missing `publishTo` setting fails at check phase
- Catches misconfiguration before any commits or tags happen

### run-tests-aggregate-fail
- Verifies that failing tests in aggregated sub-projects abort the release
- Multi-project setup with one passing and one failing test

### simple
- Tests the basic release workflow end-to-end
- Verifies version changes, git commits, and tags are created

### skip-tests
- Verifies that the `skip-tests` flag allows release despite failing tests
- Tests both the negative case (failure) and positive case (success with flag)

### snapshot-deps
- Project with a SNAPSHOT dependency
- Verifies release fails with appropriate error

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
- Verifies untracked files block the release

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
- Look for `target/` directories within test projects for build artifacts

## Tips

- Tests run in isolated temporary directories
- Each test starts with a clean slate
- Git must be configured (`user.email`, `user.name`) in tests
- Plugin is published locally before tests run
- Tests can be slow (each is a full sbt session)
