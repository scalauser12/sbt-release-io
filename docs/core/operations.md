# Operations (core)

## Recovery and rollback

### What each release step modifies

The built-in steps with expected filesystem, VCS, or remote effects are listed below.
Preflight checks (`check-clean-working-dir`, `inquire-versions`, `tag-preflight`, etc.) do not
perform these release mutations, although custom validators can have effects of their own.

| Step                     | Modifies                                                        |
| ------------------------ | --------------------------------------------------------------- |
| `run-clean`              | Local build outputs, normally under `target/`                    |
| `run-tests`              | Local compile/test outputs and project-specific effects from tests |
| `set-release-version`    | `releaseIOVersioningFile` in the working tree                    |
| `commit-release-version` | Local git history — one commit                                  |
| `tag-release`            | Local git tag                                                   |
| `publish-artifacts`      | Remote artifact repository                                      |
| `set-next-version`       | `releaseIOVersioningFile` in the working tree                    |
| `commit-next-version`    | Local git history — one commit                                  |
| `push-changes`           | Remote git branch and tags                                      |

`releaseIOVersioningFile` defaults to `version.sbt`; substitute the configured path in the
commands below if the build uses a custom version file.

### Checking current state

```bash
git log --oneline -5   # see what commits the release made
git tag                # see what tags were created
cat version.sbt        # inspect the version file
```

### Rollback: push has not happened

`git reset --hard` discards uncommitted changes in the working tree; commit or stash
anything else first.

The tag-deletion commands below assume the tag did not exist before the release and was created
by this run. Leave a kept pre-existing tag untouched. If the release overwrote a tag, restore its
exact original tag object from an object id recorded before the release, a remote, or a backup
instead of deleting it.

```bash
# Delete the tag only if this run created it
git tag -d v1.0.0

# Undo commits (2 = commit-release-version + commit-next-version)
git reset --hard HEAD~2
```

If the release failed before `commit-next-version` (only one commit was made):

```bash
git tag -d v1.0.0   # only if this run created it
git reset --hard HEAD~1
```

`run-tests` executes before `set-release-version`, so a test failure does not modify the version
file. If the release failed after `set-release-version` but before `commit-release-version`, no tag
or release commit exists yet and the configured version file is dirty in the working tree:

```bash
git checkout -- version.sbt   # or: git restore version.sbt
```

### Rollback: push has already happened

Push the revert to the same tracking remote and upstream branch that `push-changes` used. This
recipe auto-discovers both via `@{upstream}`; substitute concrete values if no upstream is set.

Pull first and confirm the last two commits are still the release commits before running this —
if anyone else pushed in the meantime, the range will revert unrelated commits.

```bash
# Inspect the tracked remote / upstream branch used by push-changes
UPSTREAM="$(git rev-parse --abbrev-ref --symbolic-full-name @{upstream})"  # e.g. origin/main
REMOTE="${UPSTREAM%%/*}"
BRANCH="${UPSTREAM#*/}"

# Delete the remote and local tag only if this run created it
git push "$REMOTE" :refs/tags/v1.0.0
git tag -d v1.0.0

# Safe revert of both release commits (git applies them newest-first)
git revert HEAD~2..HEAD
git push "$REMOTE" "HEAD:$BRANCH"
```

> **Note:** Published artifacts cannot be retracted from most repositories. Publish a corrected patch release instead.

## Related docs

- Customization: hooks, policies, and custom plugins:
  [Customization](customization.md)
- Execution model, validate/execute semantics, and sbt-release comparison:
  [Concepts](concepts.md)
- Repository build, test, and compatibility information:
  [../../README.md](../../README.md)
- Contributing:
  [../CONTRIBUTING.md](../CONTRIBUTING.md)
