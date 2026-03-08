---
name: release-notes
description: Generate categorized release notes from git history between tags
---

Generate release notes for: $ARGUMENTS

## Instructions

Generate release notes from the git commit history for the specified version range.

### Argument Parsing

The `$ARGUMENTS` value can be:
- A range: `v0.1.0..v0.2.0` or `v0.1.0 v0.2.0`
- A single tag: `v0.2.0` (generates notes from the previous tag to this one)
- Empty: generates notes from the latest tag to HEAD

If a single tag is given, find the previous tag with:
```bash
git tag --sort=-v:refname | grep -A1 "^<tag>$" | tail -1
```

If empty, use:
```bash
LATEST=$(git tag --sort=-v:refname | head -1)
```
and generate notes from `$LATEST..HEAD`.

### Gathering Commits

Run:
```bash
git log <from>..<to> --oneline --no-merges
```

Also gather PR merge commits separately:
```bash
git log <from>..<to> --oneline --merges
```

### Categorization Rules

Categorize each commit by its message prefix:

| Category | Patterns |
|----------|----------|
| **Breaking Changes** | Messages containing `BREAKING`, `breaking change`, or `!:` |
| **Features** | Start with `Add`, `Implement`, `Support`, `Introduce`, `Enable` |
| **Fixes** | Start with `Fix`, `Correct`, `Repair`, `Resolve` |
| **Improvements** | Start with `Update`, `Improve`, `Enhance`, `Optimize`, `Refactor` |
| **Documentation** | Start with `Update README`, `Add documentation`, `Clarify`, contain `README` or `docs` |
| **CI/Build** | Contain `[skip ci]`, `CI`, `build`, `scripted test`, `plugin version` |
| **Other** | Anything not matching above |

### Output Format

```markdown
## Release Notes: <version>

### Breaking Changes
- Description (#PR)

### Features
- Description (#PR)

### Bug Fixes
- Description (#PR)

### Improvements
- Description (#PR)

### Documentation
- Description (#PR)

### CI & Build
- Description (#PR)
```

Omit empty categories. Extract PR numbers from commit messages (pattern: `(#N)`).
Strip `[skip ci]` prefixes from displayed messages.

Include contributor attribution if commits have different authors:
```bash
git log <from>..<to> --format="%aN" | sort -u
```
