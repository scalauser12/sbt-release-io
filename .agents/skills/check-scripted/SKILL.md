---
name: check-scripted
description: Run and diagnose a specific sbt scripted integration test
---

Run and analyze the scripted test: $ARGUMENTS

## Instructions

Run a specific sbt scripted integration test, capture the output, and diagnose any failures.

### Argument Parsing

`$ARGUMENTS` can be:
- A test name: `simple` (searches both core and monorepo)
- A qualified name: `core/simple` or `monorepo/skip-tests`
- `all`: runs all tests across both modules
- Empty: list all available tests

### Locating the Test

Core tests are at:
  `modules/core/src/sbt-test/sbt-release-io/`

Monorepo tests are at:
  `modules/monorepo/src/sbt-test/sbt-release-io-monorepo/`

If just a test name is given, check both locations. If found in only one, use that.
If found in both, ask which module to run.
If not found in either, list available tests and suggest the closest match.

### Running the Test

For core tests:
```bash
sbt "core/scripted sbt-release-io/<test-name>"
```

For monorepo tests:
```bash
sbt "monorepo/scripted sbt-release-io-monorepo/<test-name>"
```

Capture the full output. The command may take 30-60 seconds per test.

### Running All Tests

If `$ARGUMENTS` is `all`:
```bash
sbt scripted
```

Warn the user this takes approximately 5-10 minutes.

### Diagnosing Failures

If the test fails:

1. **Read the test script** at `<test-dir>/test` to understand what the test expects
2. **Read the build.sbt** at `<test-dir>/build.sbt` to understand the test configuration
3. **Analyze the sbt output** for:
   - Compilation errors (look for `[error]`)
   - Task failures (look for `not a valid key` or `not found`)
   - Assertion failures in custom tasks
   - Git operation failures
   - Missing files (`$ exists` assertions failing)
   - Expected failures not occurring (`->` assertions)
4. **Cross-reference** the failing line in the test script with the sbt output
5. **Check version.sbt** content to verify initial version is correct
6. **Check project/plugins.sbt** to verify plugin dependency is correct

### Output Format

Report with:
- Test name and module
- Pass/Fail status
- Execution time
- If failed: the specific line in the test script that failed
- If failed: root cause analysis
- If failed: suggested fix (which file to change and how)
