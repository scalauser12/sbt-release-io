# Settings reference (core)

All release settings use the `releaseIO` prefix:

| Setting                           | Type                           | Default                                  | Description                                                                   |
| --------------------------------- | ------------------------------ | ---------------------------------------- | ----------------------------------------------------------------------------- |
| `releaseIOProcess`                | `Seq[ReleaseStepIO]`           | `ReleaseSteps.defaults`                  | Ordered sequence of release steps                                             |
| `releaseIOCrossBuild`             | `Boolean`                      | `false`                                  | Cross-build steps per `crossScalaVersions`                                  |
| `releaseIOSkipPublish`            | `Boolean`                      | `false`                                  | Skip the publish step entirely                                                |
| `releaseIOInteractive`            | `Boolean`                      | `false`                                  | Enable interactive prompts                                                    |
| `releaseIOVersionFile`            | `File`                         | `baseDirectory / "version.sbt"`          | Path to the version file                                                      |
| `releaseIOUseGlobalVersion`       | `Boolean`                      | `true`                                   | Read and write `ThisBuild / version` instead of project-scoped `version`      |
| `releaseIOReadVersion`            | `File => IO[String]`           | parses `version := "x.y.z"`              | Read version from file                                                        |
| `releaseIOVersionFileContents`    | `(File, String) => IO[String]` | writes `ThisBuild / version := "x.y.z"`  | Produce version file contents                                                 |
| `releaseIOVersionBump`            | `Version.Bump`                 | `Next`                                   | Version bump strategy (see bump types below)                                  |
| `releaseIOVersion`                | `String => String`             | strips qualifier/snapshot                | Compute release version from current                                          |
| `releaseIONextVersion`            | `String => String`             | bumps + appends `-SNAPSHOT`              | Compute next dev version                                                      |
| `releaseIOTagName`                | `String`                       | `s"v${version.value}"`                   | Git tag name                                                                  |
| `releaseIOTagComment`             | `String`                       | `s"Releasing ${version.value}"`          | Git tag comment                                                               |
| `releaseIOCommitMessage`          | `String`                       | `s"Setting version to ${version.value}"` | Release version commit message                                                |
| `releaseIONextCommitMessage`      | `String`                       | `s"Setting version to ${version.value}"` | Next version commit message                                                   |
| `releaseIOVcsSign`                | `Boolean`                      | `false`                                  | GPG-sign tags and commits                                                     |
| `releaseIOVcsSignOff`             | `Boolean`                      | `false`                                  | Add `Signed-off-by` to commits                                              |
| `releaseIOIgnoreUntrackedFiles`   | `Boolean`                      | `false`                                  | Ignore untracked files in clean check                                         |
| `releaseIOPublishArtifactsAction` | `Unit`                         | `publish`                                | Task that performs the publish                                                |
| `releaseIOPublishArtifactsChecks` | `Boolean`                      | `true`                                   | Validate `publishTo`/`skip` before publish                                    |
| `releaseIOSnapshotDependencies`   | `Seq[ModuleID]`                | auto-resolved                            | SNAPSHOT deps for validation                                                  |
| `releaseIORuntimeVersion`         | `String`                       | scope-aware `version`                    | Reads `ThisBuild / version` or `version` based on `releaseIOUseGlobalVersion` |

## Version bump types

| Bump         | Example           | Description                                                 |
| ------------ | ----------------- | ----------------------------------------------------------- |
| `Major`      | 1.0.0 → 2.0.0     | Bump major version                                          |
| `Minor`      | 1.0.0 → 1.1.0     | Bump minor version                                          |
| `Bugfix`     | 1.0.0 → 1.0.1     | Bump bugfix/patch version                                   |
| `Nano`       | 1.0.0.0 → 1.0.0.1 | Bump nano version                                           |
| `Next`       | 1.0-RC1 → 1.0-RC2 | Increment next component including prerelease **(default)** |
| `NextStable` | 1.0-RC1 → 1.0     | Increment next component, remove prerelease qualifier       |

## CLI

### Subcommands

| Subcommand | Effect |
|------------|--------|
| _(none)_ | Run the full release |
| `help` | Print usage, flags, examples, and docs links |
| `check` | Run a preflight with no release side effects: resolve versions and tags, run step validations, and print a summary — without writing version files, creating commits or tags, publishing, or pushing |

### Flags

| Flag | Effect |
| ---- | ------ |
| `with-defaults` | Use default answers for prompts |
| `skip-tests` | Skip the `run-tests` step |
| `cross` | Enable cross-building |
| `release-version <ver>` | Override the release version |
| `next-version <ver>` | Override the next snapshot version |
| `default-tag-exists-answer <o\|k\|a\|<tag-name>>` | Auto-answer the tag-exists prompt |
