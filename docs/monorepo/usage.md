# Usage (monorepo)

## Command

```
sbt "releaseIOMonorepo [help | check] [selectors] [flags] [version overrides]"
```

## Subcommands

| Subcommand | Effect |
|------------|--------|
| _(none)_ | Run the full release |
| `help` | Print usage, flags, examples, and docs links |
| `check` | Run a preflight with no release side effects |

`check` resolves projects, runs step validations, and prints a summary without writing version
files, creating commits or tags, publishing, or pushing. It also resolves versions and tags
when runtime hooks can no longer change them; otherwise those values are summarized as not
evaluated.

`help` and `check` are reserved only as the first token after `releaseIOMonorepo`. See
[Selection](#selection) below for how to select a subproject whose name collides with a
subcommand or CLI keyword.

## Selection

Use selectors to choose projects:

```
<project>
project <project>
```

Bare project ids work for ordinary names. Use `project <id>` to force project selection
when an id collides with a CLI keyword or subcommand. The reserved tokens are
`help`, `check`, `project`, `cross`, `with-defaults`, `skip-tests`, `all-changed`,
`release-version`, `next-version`, `default-tag-exists-answer`,
`default-snapshot-dependencies-answer`, `default-remote-check-failure-answer`,
`default-upstream-behind-answer`, and `default-push-answer`.

## Flags

| Flag | Description |
|------|-------------|
| `with-defaults` | Skip all interactive prompts and use computed versions |
| `skip-tests` | Skip the `run-tests` step |
| `cross` | Enable cross-build for the cross-build-aware steps (`check-snapshot-dependencies`, `run-tests`, `publish-artifacts`) |
| `all-changed` | Override change detection: release all projects |
| `default-tag-exists-answer o \| k \| a \| <new-tag>` | Auto-answer tag-conflict handling: `o` (overwrite), `k` (keep), `a` (abort), or a replacement tag name |
| `default-snapshot-dependencies-answer <y\|n>` | Auto-answer snapshot-dependency confirmation |
| `default-remote-check-failure-answer <y\|n>` | Auto-answer remote-check failure confirmation |
| `default-upstream-behind-answer <y\|n>` | Auto-answer upstream-behind confirmation |
| `default-push-answer <y\|n>` | Auto-answer the final push confirmation |

## Version overrides

Pin specific release or next versions per project:

```
release-version <project>=<version>
next-version <project>=<version>
```

## Examples

```bash
# Release all changed projects with default versions
sbt "releaseIOMonorepo with-defaults"

# Release only core and api
sbt "releaseIOMonorepo core api with-defaults"

# Select a keyword-like project id explicitly — `cross` is also the cross-build CLI flag,
# so `project cross` disambiguates the project name from the flag
sbt "releaseIOMonorepo project cross with-defaults"

# Pin versions per project
sbt "releaseIOMonorepo with-defaults release-version core=1.0.0 next-version core=1.1.0-SNAPSHOT"

# Release all projects regardless of changes
sbt "releaseIOMonorepo all-changed with-defaults"

# Enable cross-building
sbt "releaseIOMonorepo cross with-defaults"

# Skip tests
sbt "releaseIOMonorepo skip-tests with-defaults"

# Auto-answer push confirmation
sbt "releaseIOMonorepo core with-defaults default-push-answer y release-version core=1.0.0 next-version core=1.1.0-SNAPSHOT"
```
