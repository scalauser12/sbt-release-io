# Operations (monorepo)

## Recovery and rollback

### What each release phase modifies

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

```bash
# Delete tags created by tag-releases
git tag -d core/v0.1.0
git tag -d api/v0.1.0

# Undo commits (2 = commit-release-versions + commit-next-versions; use HEAD~1 if only one was made)
git reset --hard HEAD~2
```

### Rollback: push has already happened

```bash
# Delete remote tags
git push origin :refs/tags/core/v0.1.0
git push origin :refs/tags/api/v0.1.0

# Safe revert (keeps history)
git revert HEAD     # revert commit-next-versions
git revert HEAD~1   # revert commit-release-versions
git push origin main
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
