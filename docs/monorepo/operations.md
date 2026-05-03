# Operations (monorepo)

## Recovery and rollback

The tag names in the recipes below (`core/v0.1.0`, `api/v0.1.0`) are illustrative per-project
tags. Substitute the actual tag names your release created.

### What each release step modifies

Only mutating steps are listed; preflight checks (`check-clean-working-dir`,
`inquire-versions`, `tag-preflight`, `resolve-release-order`, `detect-or-select-projects`,
etc.) leave no state behind.

| Step | Modifies |
|------|----------|
| `set-release-version` | Per-project `version.sbt` files (working tree) |
| `commit-release-versions` | Local git history — one commit |
| `tag-releases` | Local git tags |
| `publish-artifacts` | Remote artifact repository |
| `set-next-version` | Per-project `version.sbt` files (working tree) |
| `commit-next-versions` | Local git history — one commit |
| `push-changes` | Remote git branch and tags |

### Checking current state

```bash
git log --oneline -5   # see what commits the release made
git tag                # see what tags were created
cat core/version.sbt   # inspect a version file
```

### Rollback: push has not happened

Per-project failure isolation means a `tag-releases` step that fails mid-way can leave *some*
projects tagged but not others. List what actually exists before deleting:

```bash
git tag --list 'core/v*' 'api/v*'   # adjust patterns to your projects / tag scheme
```

`git reset --hard` discards uncommitted changes in the working tree; commit or stash anything
else first.

```bash
# Delete tags created by tag-releases
git tag -d core/v0.1.0
git tag -d api/v0.1.0

# Undo commits (2 = commit-release-versions + commit-next-versions; use HEAD~1 if only one was made)
git reset --hard HEAD~2
```

If the release failed before any commit (e.g., during `set-release-version` or `run-tests`),
no tag exists yet and only the per-project version files are dirty in the working tree:

```bash
git checkout -- '*/version.sbt'   # or list the affected paths explicitly
```

### Rollback: push has already happened

Push the revert to the same tracking remote and upstream branch that `push-changes` used.
This recipe auto-discovers your tracking remote and branch via `@{upstream}`; substitute
concrete values if `@{upstream}` isn't set.

Pull first and confirm the last two commits are still the release commits before running this
— if anyone else pushed in the meantime, the range will revert unrelated commits. See also
[per-project failure isolation](concepts.md#per-project-failure-isolation) for why partial
tag sets can occur.

```bash
# Inspect the tracked remote / upstream branch used by push-changes
UPSTREAM="$(git rev-parse --abbrev-ref --symbolic-full-name @{upstream})"  # e.g. origin/main
REMOTE="${UPSTREAM%%/*}"
BRANCH="${UPSTREAM#*/}"

# Delete remote tags
git push "$REMOTE" :refs/tags/core/v0.1.0
git push "$REMOTE" :refs/tags/api/v0.1.0

# Delete the same tags locally so a retry from this checkout starts clean
git tag -d core/v0.1.0
git tag -d api/v0.1.0

# Safe revert of both release commits (git applies them newest-first)
git revert HEAD~2..HEAD
git push "$REMOTE" "HEAD:$BRANCH"
```

> **Note:** Published artifacts cannot be retracted from most repositories. Publish a corrected patch release instead.

## Related docs

- Customization: hooks, policies, and custom plugins:
  [Customization](customization.md)
- Execution model, failure isolation, and ordering:
  [Concepts](concepts.md)
- Repository build, test, and compatibility information:
  [../../README.md](../../README.md)
- Scripted test inventory:
  [../../modules/monorepo/src/sbt-test/README.md](../../modules/monorepo/src/sbt-test/README.md)
- Contributing:
  [../CONTRIBUTING.md](../CONTRIBUTING.md)
