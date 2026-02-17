# Scripted Tests for sbt-release-io

This directory contains scripted tests for the sbt-release-io plugin. These tests verify that the plugin works correctly in real-world scenarios.

## Test Structure

Each test is located in `sbt-release-io/<test-name>/` and contains:

- `build.sbt` - Project configuration for the test
- `version.sbt` - Version file that the plugin will modify
- `project/plugins.sbt` - Loads the sbt-release-io plugin
- `test` - Test script with commands to execute

## Available Tests

### simple
Tests the basic release workflow:
- Initialize git repository
- Run release with default settings
- Verify version changes, commits, and tags

### snapshot-deps
Tests snapshot dependency detection:
- Project with a SNAPSHOT dependency
- Verifies release fails with appropriate error

### untracked-files
Tests `releaseIgnoreUntrackedFiles` setting:
- Creates untracked files in working directory
- Verifies release succeeds when setting is enabled

### empty-commit
Tests handling of empty commits:
- Version file already at release version
- Verifies no-op commit is handled gracefully
- Verifies "No changes to commit" message is logged

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
