# Change detection (monorepo)

The plugin detects which projects have changed since their last release tag using `git diff`.

## How it works

1. For each project, find the most recent matching tag. The tag pattern comes from
   `releaseIOMonorepoVcsTagName(project.name, "*")`, so the default pattern is
   `<projectName>/v*` (e.g., `core/v*`); if you override the tag name function, the
   detection pattern follows automatically. Your tag function must produce a
   `git tag -l`-compatible glob when called with `"*"` as the version — most simple
   `s"…/v$ver"` shapes work as-is.
2. If no tag exists, the project is treated as changed (first release).
3. Run `git diff --name-only <tag>..HEAD -- <projectDir>`.
4. Filter out each project's own version file and any files or directories in
   `releaseIOMonorepoDetectionExcludes` (see [Excluding files or directories from
   detection](#excluding-files-or-directories-from-detection)).
5. If any significant files remain, the project is changed.

If any path in `releaseIOMonorepoDetectionSharedPaths` (see below) has changed since
a project's last tag, that project is also marked as changed.

Any git command failure conservatively treats the project as changed.

## Downstream dependents

By default, only projects with direct file changes are released. If `core` changes and `api` depends on `core`, `api` is **not** released unless it also has file changes.

Enable `releaseIOMonorepoDetectionIncludeDownstream` to automatically include all transitive downstream dependents of changed projects:

```scala
releaseIOMonorepoDetectionIncludeDownstream := true
```

With this setting, if `core` changes and `api` depends on `core` and `web` depends on `api`, all three are released. This works with both the built-in git-based detector and custom change detectors.

## Shared paths

Some files affect every project even when they sit outside any project's directory — the
root `build.sbt`, the `project/` directory with `plugins.sbt` and build-definition Scala
files, CI workflows, etc. The plugin treats these as **shared paths**: if any shared path
has changed since a project's last tag, that project is marked as changed.

The default is:

```scala
releaseIOMonorepoDetectionSharedPaths := Seq("build.sbt", "project/")
```

This means that editing your root `build.sbt` or anything under `project/` — adding a
plugin, bumping sbt, changing a shared library version — causes **every** project to be
released on the next run. That's usually what you want: a build-system change affects all
outputs.

To change this behavior, override the setting with the root-relative paths you want to
track, or set it to `Seq.empty` to disable the shared-path check entirely:

```scala
// Track only root build.sbt, not project/
releaseIOMonorepoDetectionSharedPaths := Seq("build.sbt")

// Track nothing as shared — only per-project file changes matter
releaseIOMonorepoDetectionSharedPaths := Seq.empty
```

Shared paths are checked per tag, so projects that all point at the same last-tag commit
share a single git diff lookup. Shared-path detection only runs for projects that have a
prior tag — first-release projects are already marked as changed.

Shared paths only apply to the built-in detector and are ignored when
`releaseIOMonorepoDetectionChangeDetector` is set.

## Version overrides force-include projects

When using change detection, providing a CLI version override for a project forces it into the release set even if it has no detected changes. For example:

```bash
sbt "releaseIOMonorepo with-defaults release-version api=1.0.0 next-version api=1.1.0-SNAPSHOT"
```

This releases `api` at version `1.0.0` regardless of whether change detection found changes in `api`. Force-included projects do not trigger downstream expansion — only projects detected as changed contribute to `releaseIOMonorepoDetectionIncludeDownstream` expansion.

## Custom change detector

The example below assumes `build.sbt` scope, where `ProjectRef`, `File`, and `State` are
already in scope. In `.scala` build sources under `project/`, add `import sbt.*`.

```scala
import _root_.cats.effect.IO

releaseIOMonorepoDetectionChangeDetector := Some((ref: ProjectRef, baseDir: File, state: State) =>
  IO.pure(ref.project == "core") // only release "core"
)
```

On detector error, the project is conservatively treated as changed.

> **Note:** A custom detector **replaces** the built-in detection entirely. Settings like
> `releaseIOMonorepoDetectionExcludes` only apply to the built-in detector and are
> ignored when a custom detector is set.

## Excluding files or directories from detection

```scala
// In the root project settings — exclude a subproject's generated changelog
// and an entire generated directory tree
releaseIOMonorepoDetectionExcludes := Seq(
  (core / baseDirectory).value / "CHANGELOG.md",
  (core / baseDirectory).value / "target" / "generated-docs"
)
```

This setting is read from the **root project** scope, so use `(subproject / baseDirectory).value`
to reference subproject directories. Excluded directory paths suppress nested files using prefix
matching. Per-project version files are always excluded automatically.
This setting only applies to the built-in detector and is ignored when `releaseIOMonorepoDetectionChangeDetector` is set.

## First releases and renamed tag schemes

On a brand-new repo with no prior release tags, change detection marks all projects as
changed — this is expected and is how first releases work.

Changing `releaseIOMonorepoVcsTagName` after a previous release leaves the new pattern
unable to match the old tags, so the detector falls back to first-release behavior for
affected projects until a new baseline tag exists. To re-establish that baseline:

- Tag the current commit under the new scheme. For each project, use the
  configured `releaseIOMonorepoVcsTagName` to compute the tag (the default is
  `<name>/v<version>`):

  ```bash
  git tag core/v1.0.0 -m "baseline tag for core under new scheme"
  git tag api/v1.0.0  -m "baseline tag for api under new scheme"
  git push --tags
  ```

- Re-run `sbt "releaseIOMonorepo check"`. Subsequent runs detect changes
  against these baseline tags, so only projects with commits since each
  baseline are flagged as changed.
