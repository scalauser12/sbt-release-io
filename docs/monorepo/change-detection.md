# Change detection (monorepo)

The plugin detects which projects have changed since their last release tag using `git diff`.

## How it works

1. For each project, find the most recent matching tag:
   - Pattern `<projectName>/v*` (e.g., `core/v*`)
2. If no tag exists, the project is treated as changed (first release).
3. Run `git diff --name-only <tag>..HEAD -- <projectDir>`.
4. Filter out version files and any files in `releaseIOMonorepoDetectChangesExcludes`.
5. If any significant files remain, the project is changed.

Any git command failure conservatively treats the project as changed.

## Downstream dependents

By default, only projects with direct file changes are released. If `core` changes and `api` depends on `core`, `api` is **not** released unless it also has file changes.

Enable `releaseIOMonorepoIncludeDownstream` to automatically include all transitive downstream dependents of changed projects:

```scala
releaseIOMonorepoIncludeDownstream := true
```

With this setting, if `core` changes and `api` depends on `core` and `web` depends on `api`, all three are released. This works with both the built-in git-based detector and custom change detectors.

## Version overrides force-include projects

When using change detection, providing a CLI version override for a project forces it into the release set even if it has no detected changes. For example:

```bash
sbt "releaseIOMonorepo with-defaults release-version api=1.0.0 next-version api=1.1.0-SNAPSHOT"
```

This releases `api` at version `1.0.0` regardless of whether change detection found changes in `api`. Force-included projects do not trigger downstream expansion — only projects detected as changed contribute to `releaseIOMonorepoIncludeDownstream` expansion.

## Custom change detector

```scala
releaseIOMonorepoChangeDetector := Some((ref: ProjectRef, baseDir: File, state: State) =>
  IO.pure(ref.project == "core") // only release "core"
)
```

On detector error, the project is conservatively treated as changed.

> **Note:** A custom detector **replaces** the built-in detection entirely. Settings like
> `releaseIOMonorepoDetectChangesExcludes` only apply to the built-in detector and are
> ignored when a custom detector is set.

## Excluding files from detection

```scala
// In the root project settings — exclude a subproject's generated changelog
releaseIOMonorepoDetectChangesExcludes := Seq(
  (core / baseDirectory).value / "CHANGELOG.md"
)
```

This setting is read from the **root project** scope, so use `(subproject / baseDirectory).value`
to reference subproject directories. Per-project version files are always excluded automatically.
This setting only applies to the built-in detector and is ignored when `releaseIOMonorepoChangeDetector` is set.

## First release shows no projects changed

On a brand-new repo with no prior release tags, change detection marks all projects as changed — this is expected. If tags exist but under a different scheme, some projects may appear unchanged. Use `all-changed` to bypass detection, or disable it permanently with `releaseIOMonorepoDetectChanges := false`.
