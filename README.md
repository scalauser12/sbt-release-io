# sbt-release-io

An sbt plugin that wraps [sbt-release](https://github.com/sbt/sbt-release) with cats-effect IO for better functional programming support and error handling.

## Features

- **IO-based release steps**: All release steps return `IO[ReleaseContext]` for composable, referentially transparent operations
- **Full compatibility**: Delegates to upstream sbt-release 1.4.0 for VCS operations, version bumping, and settings
- **Flexible step composition**: Create custom release steps using cats-effect IO
- **Better error handling**: Graceful failure handling with the IO monad
- **Cross-build support**: Run release steps across multiple Scala versions
- **Upstream-style helper commands**: Run individual release phases with `release-*` commands
- **Configurable**: Respects all upstream sbt-release settings (commit messages, signing, version bumping, etc.)

## Installation

Add to `project/plugins.sbt`:

```scala
addSbtPlugin("io.github.sbt-release-io" % "sbt-release-io" % "0.1.0-SNAPSHOT")
```

## Usage

### Basic Release

Run the release process:

```bash
sbt releaseIO
```

With command-line options:

```bash
# Use default answers for all prompts
sbt "releaseIO with-defaults"

# Skip tests
sbt "releaseIO skip-tests"

# Enable cross-building
sbt "releaseIO cross"

# Specify versions
sbt "releaseIO release-version 1.0.0 next-version 1.1.0-SNAPSHOT"

# Combine options
sbt "releaseIO with-defaults skip-tests release-version 1.0.0"
```

### Extra Commands

The plugin also provides upstream-style helper commands for running individual phases:

```bash
sbt release-vcs-checks
sbt release-check-snapshot-dependencies
sbt release-inquire-versions
sbt release-set-release-version
sbt release-set-next-version
sbt release-commit-release-version
sbt release-commit-next-version
sbt release-tag-release
sbt release-push-changes
```

### Configuration

In `build.sbt`:

```scala
import io.release.ReleasePluginIO.autoImport._
import io.release.steps.ReleaseSteps

// Use default release steps (recommended)
releaseIOProcess := ReleaseSteps.defaults

// Or customize the release process
releaseIOProcess := Seq(
  ReleaseSteps.initializeVcs,
  ReleaseSteps.checkCleanWorkingDir,
  ReleaseSteps.checkSnapshotDependencies,
  ReleaseSteps.inquireVersions,
  ReleaseSteps.runTests,
  ReleaseSteps.setReleaseVersion,
  ReleaseSteps.commitReleaseVersion,
  ReleaseSteps.tagRelease,
  ReleaseSteps.publishArtifacts,
  ReleaseSteps.setNextVersion,
  ReleaseSteps.commitNextVersion,
  ReleaseSteps.pushChanges
)

// Enable cross-building by default
releaseIOCrossBuild := true

// Skip publish during release
releaseIOSkipPublish := true
```

### Custom Release Steps

Create custom release steps using the IO-based API:

```scala
import cats.effect.IO
import io.release.{ReleaseContext, ReleaseStepIO}

// Simple custom step
val printBanner = ReleaseStepIO.io("print-banner") { ctx =>
  IO {
    println("=== RELEASE IN PROGRESS ===")
    ctx
  }
}

// Step with VCS operations
val validateBranch = ReleaseStepIO.io("validate-branch") { ctx =>
  ctx.vcs match {
    case Some(vcs) =>
      IO.blocking(vcs.currentBranch).flatMap { branch =>
        if (branch == "main") IO.pure(ctx)
        else IO.raiseError(new RuntimeException(s"Must release from main, not $branch"))
      }
    case None =>
      IO.raiseError(new RuntimeException("VCS not initialized"))
  }
}

// Compose custom steps with built-in ones
releaseIOProcess := Seq(
  printBanner,
  ReleaseSteps.initializeVcs,
  validateBranch,
  ReleaseSteps.checkCleanWorkingDir,
  // ... more steps
)
```

See `examples/CustomStepExamples.scala` for more examples.

### Upstream sbt-release Settings

All upstream sbt-release settings are supported:

```scala
import sbtrelease.ReleasePlugin.autoImport._

releaseVersionBump := sbtrelease.Version.Bump.Minor
releaseTagName := s"v${version.value}"
releaseTagComment := s"Releasing ${version.value}"
releaseCommitMessage := s"Setting version to ${version.value}"
releaseNextCommitMessage := s"Setting version to ${version.value}"
releaseIgnoreUntrackedFiles := false
releaseVcsSign := false
releaseVcsSignOff := false
releaseVersionFile := baseDirectory.value / "version.sbt"
releaseUseGlobalVersion := true
releasePublishArtifactsAction := publish.value
```

## Default Release Steps

The default release process includes:

1. **initializeVcs** - Detect and initialize VCS (Git/Mercurial/Subversion)
2. **checkCleanWorkingDir** - Verify no uncommitted changes
3. **checkSnapshotDependencies** - Verify no snapshot dependencies
4. **inquireVersions** - Determine release and next versions
5. **runTests** - Run tests (unless `skip-tests`)
6. **setReleaseVersion** - Update version.sbt to release version
7. **commitReleaseVersion** - Commit version change
8. **tagRelease** - Create Git tag
9. **publishArtifacts** - Publish to repository
10. **setNextVersion** - Update version.sbt to next snapshot
11. **commitNextVersion** - Commit version change
12. **pushChanges** - Push commits and tags to remote

## Testing

This plugin includes comprehensive scripted tests.

Run all tests:
```bash
sbt scripted
```

Run specific test:
```bash
sbt "scripted sbt-release-io/simple"
```

See `src/sbt-test/README.md` for test documentation.

## Compatibility

- **sbt**: 1.x
- **Scala**: 2.12
- **sbt-release**: 1.4.0
- **cats-effect**: 3.6.3

## Differences from sbt-release

1. **IO-based**: All steps return `IO[ReleaseContext]` instead of `State => State`
2. **Better error handling**: Failures are captured in IO and can be handled functionally
3. **Context threading**: Release context (VCS, versions, etc.) is threaded through steps
4. **Cross-build improvements**: Fresh state extraction on each iteration
5. **Empty commit handling**: Gracefully handles no-op commits
6. **Enhanced failure detection**: Improved FailureCommand detection matching upstream

The plugin maintains full compatibility with upstream sbt-release settings and behavior.

## Contributing

Contributions are welcome! Please ensure:

1. All tests pass (`sbt scripted`)
2. Code compiles (`sbt compile`)
3. No breaking changes to public API
4. Add tests for new features

## License

This project follows the same license as sbt-release.

## Acknowledgments

Built on top of [sbt-release](https://github.com/sbt/sbt-release) by sbt organization.
