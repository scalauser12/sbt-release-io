# Operations (monorepo)

## Recovery and rollback

The tag names in the recipes below (`core/v0.1.0`, `api/v0.1.0`) are illustrative per-project
tags. Substitute the actual tag names your release created.

The tag-deletion commands assume those tags did not exist before the release and were created by
this run. Leave a kept pre-existing tag untouched. If the release overwrote a tag, restore its
exact original tag object from an object id recorded before the release, a remote, or a backup
instead of deleting it.

### What each release step modifies

The built-in steps with expected filesystem, VCS, or remote effects are listed below.
Preflight checks (`check-clean-working-dir`, `inquire-versions`, `tag-preflight`,
`plan-tag-names`, `resolve-release-order`, `detect-or-select-projects`, etc.) do not perform
these release mutations, although custom validators can have effects of their own.

| Step | Modifies |
|------|----------|
| `run-clean` | Local build outputs removed by sbt's clean task (normally under `target/`) |
| `run-tests` | Local compile/test outputs and any project-specific effects caused by tests |
| `set-release-version` | Configured per-project version files (working tree) |
| `commit-release-versions` | Local git history — one commit |
| `tag-releases` | Local git tags |
| `publish-artifacts` | Remote artifact repository |
| `set-next-version` | Configured per-project version files (working tree) |
| `commit-next-versions` | Local git history — one commit |
| `push-changes` | Remote git branch and tags |

### Checking current state

```bash
git log --oneline -5   # see what commits the release made
git tag                # see what tags were created
cat core/version.sbt   # inspect one configured version file; substitute its actual path
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
# Delete only tags that this run created
git tag -d core/v0.1.0
git tag -d api/v0.1.0

# Undo commits (2 = commit-release-versions + commit-next-versions; use HEAD~1 if only one was made)
git reset --hard HEAD~2
```

If the default lifecycle fails during `run-clean` or `run-tests`, it has not written any
version files or created a release commit or tag. `run-clean` may already have removed local
build outputs, which sbt recreates on the next build; project tests may have their own effects.

If it fails during `set-release-version`, no release commit or tag exists yet, but some
configured per-project version files may be dirty in the working tree. Inspect `git status`,
then restore the exact paths returned by `releaseIOMonorepoVersioningFile` for the affected
projects. For example, a mixed-format build might use:

```bash
git checkout -- core/version.sbt services/api/version.properties
```

Custom hooks can add their own mutations before or during these phases; include those when
assessing and restoring a failed release.

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

# Delete only remote tags that this run created
git push "$REMOTE" :refs/tags/core/v0.1.0
git push "$REMOTE" :refs/tags/api/v0.1.0

# Delete the same newly created tags locally so a retry starts clean
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
