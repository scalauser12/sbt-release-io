---
name: scripted-test-generator
description: Generate sbt scripted test scenarios for sbt-release-io plugin
disable-model-invocation: true
---

Generate a new sbt scripted test for: $ARGUMENTS

## Instructions

Create a new scripted test under `src/sbt-test/sbt-release-io/<test-name>/` following the exact conventions of the existing 15 test scenarios.

### Required Files

Every test MUST have these files:

#### 1. `version.sbt`
```scala
version := "0.1.0-SNAPSHOT"
```

#### 2. `project/plugins.sbt`
```scala
sys.props.get("plugin.version") match {
  case Some(ver) => addSbtPlugin("io.github.sbt-release-io" % "sbt-release-io" % ver)
  case _ => sys.error("Plugin version not set")
}
```

#### 3. `.gitignore`
```
target/
project/target/
project/project/
*.class
*.log
test
```
Add any test-specific generated files (marker files, etc.) to .gitignore as well.

#### 4. `build.sbt`

Must include:
```scala
import scala.sys.process._

name := "<test-name>"
scalaVersion := "2.12.18"

// Skip push and publish steps in tests
releaseIOProcess := releaseIOProcess.value.filterNot { step =>
  step.name == "push-changes" || step.name == "publish-artifacts"
}

// Ignore untracked files in tests (test script itself is untracked)
releaseIgnoreUntrackedFiles := true
```

Add custom verification tasks as `taskKey[Unit]` or `inputKey[Unit]` for assertions that need sbt context. Use `scala.sys.process._` for shell commands in verification tasks. See `src/sbt-test/sbt-release-io/simple/build.sbt` for examples of `checkGitTag` and `checkGitCommitCount` task definitions.

For custom release steps, use:
```scala
import _root_.io.release.{ReleaseContext, ReleaseStepIO}
import _root_.io.release.steps.ReleaseSteps
```

#### 5. `test` (no file extension)

The scripted test script. Always starts with git initialization:
```bash
# Initialize git repository
$ exec git init
$ exec git config user.email "test@example.com"
$ exec git config user.name "Test User"
$ exec git add .gitignore build.sbt version.sbt project/plugins.sbt
$ exec git commit -m "Initial commit"
```

If the test has source files in `src/`, add them to the git add line.

### Test Script Syntax

| Syntax | Purpose | Example |
|--------|---------|---------|
| `$ exec <cmd>` | Run shell command | `$ exec git init` |
| `> <sbt-cmd>` | sbt command must succeed | `> releaseIO with-defaults` |
| `-> <sbt-cmd>` | sbt command must fail | `-> releaseIO with-defaults` |
| `$ exists <file>` | Assert file exists | `$ exists marker-file` |
| `$ absent <file>` | Assert file does not exist | `$ absent marker-file` |
| `$ delete <file>` | Delete a file | `$ delete marker-file` |
| `$ touch <file>` | Create empty file | `$ touch untracked.txt` |
| `# comment` | Comment | `# Verify tag was created` |

### Common releaseIO Arguments

```bash
> releaseIO with-defaults
> releaseIO with-defaults release-version 0.1.0 next-version 0.2.0-SNAPSHOT
> releaseIO with-defaults skip-tests
> releaseIO with-defaults default-tag-exists-answer o
> releaseIO with-defaults cross release-version 0.2.0 next-version 0.3.0-SNAPSHOT
```

### Multi-Scenario Tests

When testing multiple scenarios, reset git state between them:
```bash
$ exec git tag -d v0.1.0
$ exec git reset --hard HEAD~2
> reload
```

### Reference Example

Read the `simple` test for a complete working example:
- `src/sbt-test/sbt-release-io/simple/build.sbt` — build config with custom verification tasks
- `src/sbt-test/sbt-release-io/simple/test` — test script with git init, release, and assertions
- `src/sbt-test/sbt-release-io/simple/version.sbt` — starting version
- `src/sbt-test/sbt-release-io/simple/project/plugins.sbt` — plugin reference
- `src/sbt-test/sbt-release-io/simple/.gitignore` — standard gitignore

Also review other tests for advanced patterns:
- `check-phase` — custom steps with check/action phases and marker files
- `tag-default` — multi-scenario test with git reset between scenarios
- `cross` — cross-build testing with source files
- `tasks-as-steps` — converting sbt tasks/commands into release steps

## Checklist Before Finishing

1. All 5 required files are created
2. Test name uses kebab-case
3. Git initialization includes all project files
4. Custom verification tasks have descriptive names and assertion messages
5. Comments explain each section of the test script
6. Any marker/generated files are added to .gitignore
