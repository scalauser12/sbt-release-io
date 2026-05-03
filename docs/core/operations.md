# Operations (core)

## Recovery and rollback

### What each release step modifies

Only mutating steps are listed; preflight checks (`check-clean-working-dir`,
`inquire-versions`, `tag-preflight`, etc.) leave no state behind.

| Step                     | Modifies                       |
| ------------------------ | ------------------------------ |
| `set-release-version`    | `version.sbt` (working tree)   |
| `commit-release-version` | Local git history — one commit |
| `tag-release`            | Local git tag                  |
| `publish-artifacts`      | Remote artifact repository     |
| `set-next-version`       | `version.sbt` (working tree)   |
| `commit-next-version`    | Local git history — one commit |
| `push-changes`           | Remote git branch and tags     |

### Checking current state

```bash
git log --oneline -5   # see what commits the release made
git tag                # see what tags were created
cat version.sbt        # inspect the version file
```

### Rollback: push has not happened

`git reset --hard` discards uncommitted changes in the working tree; commit or stash
anything else first.

```bash
# Delete the tag created by tag-release
git tag -d v1.0.0

# Undo commits (2 = commit-release-version + commit-next-version)
git reset --hard HEAD~2
```

If the release failed before `commit-next-version` (only one commit was made):

```bash
git tag -d v1.0.0
git reset --hard HEAD~1
```

If the release failed before any commit (e.g., during `set-release-version` or
`run-tests`), no tag exists yet and only `version.sbt` is dirty in the working tree:

```bash
git checkout -- version.sbt   # or: git restore version.sbt
```

### Rollback: push has already happened

Pull first and confirm the last two commits are still the release commits before
running this — if anyone else pushed in the meantime, the range will revert
unrelated commits.

```bash
# Delete the remote tag
git push origin :refs/tags/v1.0.0

# Safe revert of both release commits (git applies them newest-first)
git revert HEAD~2..HEAD
git push origin HEAD          # replace HEAD with your branch name if needed
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
