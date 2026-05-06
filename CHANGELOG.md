# Changelog

This changelog aggregates the published GitHub releases for
[`scalauser12/sbt-release-io`](https://github.com/scalauser12/sbt-release-io).
This file is the canonical release history for the repository.

## v0.13.1

Published: 2026-05-06
GitHub release:
[v0.13.1](https://github.com/scalauser12/sbt-release-io/releases/tag/v0.13.1)

`v0.13.1` is a patch release for both plugins that keeps the public contract
unchanged while simplifying internal release command, lifecycle, tagging, and
monorepo preflight boundaries.

### Compatibility notes

- No public API or release-flow behavior changes are intended in this release.

### Improvements

- Consolidate core and monorepo command parsing through the shared
  `ReleaseCommandCli` helper while preserving the existing command grammar.
- Extract tag handling into `TagSteps` and split monorepo preflight/version
  helpers into smaller internal modules.
- Rename several internal support types (`PluginEntrypoint`, `VersionWorkflow`,
  and related helpers) for clearer runtime and workflow boundaries.
- Simplify lifecycle compiler built-in phase construction and freeze-gate naming.

### Documentation

- Refresh agent-facing architecture references after the internal file moves.
- Refresh the root README, module READMEs, and published walkthrough/getting-started docs to
  reference `0.13.1`.

### Verification

- `git diff --check`
- sbt 1.12.3: `sbt -Dsbt.version=1.12.3 scalafmtCheckAll scalafmtSbtCheck`
- sbt 1.12.3: `sbt -Dsbt.version=1.12.3 test`
- sbt 2.0.0-RC9: `./bin/sbt2-clean test`
- sbt 1.12.3: `sbt -Dsbt.version=1.12.3 'core/publishLocal' 'monorepo/publishLocal'`
- sbt 2.0.0-RC9: `./bin/sbt2-clean 'core/publishLocal' 'monorepo/publishLocal'`

## v0.13.0

Published: 2026-05-04
GitHub release:
[v0.13.0](https://github.com/scalauser12/sbt-release-io/releases/tag/v0.13.0)

`v0.13.0` is a minor release for both plugins that finalizes the intent-named hook
factory API by removing the deprecated `.io`, `.action`, and `.actionTracked`
constructors that were marked `@deprecated` in v0.12.2. This is a
source-incompatible change for build code that still uses those factories.

### Breaking changes

- Remove the deprecated factories `.io`, `.action`, and `.actionTracked` from
  `ReleaseHookIO`, `ReleaseResourceHookIO`, `MonorepoGlobalHookIO`,
  `MonorepoProjectHookIO`, `MonorepoGlobalResourceHookIO`, and
  `MonorepoProjectResourceHookIO`.

### Migration

| Old factory | New factory | Argument-shape change |
|---|---|---|
| `.io(name)(ctx => f)` | `.transform(name)(ctx => f)` | none (plain hooks) |
| `.action(name)(ctx => f)` | `.sideEffect(name)(ctx => f)` | none (plain hooks) |
| `.actionTracked(name)(handle => f)` | `.resumable(name)(handle => f)` | none |
| `.io[T](name)(t => ctx => f)` (resource) | `.transform[T](name)((t, ctx) => f)` | curry → flat |
| `.action[T](name)(t => ctx => f)` (resource) | `.sideEffect[T](name)((t, ctx) => f)` | curry → flat |
| `.io(name)((ctx, p) => f)` (project) | `.transform(name)((p, ctx) => f)` | argument order swap |
| `.action(name)((ctx, p) => f)` (project) | `.sideEffect(name)((p, ctx) => f)` | argument order swap |
| `.io[T](name)(t => (ctx, p) => f)` (project resource) | `.transform[T](name)((t, p, ctx) => f)` | flatten + reorder |

The case-class constructors (`ReleaseHookIO(name, execute, validate)` etc.) remain
public; user code that builds hooks via the case class continues to compile.
`.ioTracked` remains supported as the lower-level escape hatch when direct
`TrackedContextHandle` access is needed.

### Documentation

- Drop the deprecated-factory note from `docs/core/customization.md` and
  `docs/monorepo/customization.md`.
- Refresh the root README, module READMEs, and published walkthrough/getting-started docs to
  reference `0.13.0`.

### Verification

- `git diff --check`
- sbt 1.12.3: `sbt -Dsbt.version=1.12.3 scalafmtCheckAll scalafmtSbtCheck`
- sbt 1.12.3: `sbt -Dsbt.version=1.12.3 test`
- sbt 2.0.0-RC9: `./bin/sbt2-clean test`
- sbt 1.12.3: `sbt -Dsbt.version=1.12.3 'core/publishLocal' 'monorepo/publishLocal'`
- sbt 2.0.0-RC9: `./bin/sbt2-clean 'core/publishLocal' 'monorepo/publishLocal'`

## v0.12.3

Published: 2026-04-29
GitHub release:
[v0.12.3](https://github.com/scalauser12/sbt-release-io/releases/tag/v0.12.3)

`v0.12.3` is a patch release for both plugins that tightens publish validation around
version-dependent publish gates and keeps the sbt 2 release lane aligned with the tag-driven
publishing workflow.

### Fixes

- Apply tentative, non-prompting release-version overlays during validate-time publish checks so
  default flows catch `publish / skip := isSnapshot.value` builds before any release mutations.
- Preserve late-bound/session settings across publish validation and execution so hook-installed
  publish settings, manifest metadata, and version overlays remain visible to the intended steps.

### Improvements

- Refine the sbt 2 clean lane and tag-driven release workflow so local IDE-generated build files
  do not interfere with sbt 2 verification.
- Clarify monorepo logging wrappers around shared release diagnostics.

### Documentation

- Refresh the root README, module READMEs, and published walkthrough/getting-started docs to
  reference `0.12.3`.

### Verification

- `git diff --check`
- sbt 1.12.3: `sbt -Dsbt.version=1.12.3 scalafmtCheckAll scalafmtSbtCheck`
- sbt 1.12.3: `sbt -Dsbt.version=1.12.3 test`
- sbt 2.0.0-RC9: `./bin/sbt2-clean test`
- sbt 1.12.3: `sbt -Dsbt.version=1.12.3 'core/publishLocal' 'monorepo/publishLocal'`
- sbt 2.0.0-RC9: `./bin/sbt2-clean 'core/publishLocal' 'monorepo/publishLocal'`

## v0.12.2

Published: 2026-04-29
GitHub release:
[v0.12.2](https://github.com/scalauser12/sbt-release-io/releases/tag/v0.12.2)

`v0.12.2` is a patch release for both plugins that clarifies the HookAPI
deprecation path and migration guidance without removing any deprecated methods.

### Compatibility notes

- No deprecated hook constructors are removed in this release.
- `.ioTracked` remains supported as the lower-level `TrackedContextHandle` escape hatch.
- `.actionTracked` is now deprecated in favor of `resumable`.
- The new deprecation warnings may affect builds that compile with fatal warnings.

### Improvements

- Clarify that `sideEffect`, `transform`, and `resumable` use the tracked execute path,
  while `precondition` is validate-only and leaves `execute` as a no-op.
- Clarify that `resumable` preserves recoverable context checkpoints but does not make
  arbitrary external side effects idempotent.
- Tighten the HookAPI ScalaDoc and customization docs across core, monorepo, and
  resource-aware hook types.

### Documentation

- Refresh the root README, module READMEs, and published walkthrough/getting-started docs to
  reference `0.12.2`.

### Verification

- `git diff --check`
- sbt 1.12.3: `sbt -Dsbt.version=1.12.3 scalafmtCheckAll scalafmtSbtCheck`
- sbt 1.12.3: `sbt -Dsbt.version=1.12.3 test`
- sbt 2.0.0-RC9: `./bin/sbt2-clean test`
- sbt 1.12.3: `sbt -Dsbt.version=1.12.3 'core/publishLocal' 'monorepo/publishLocal'`
- sbt 2.0.0-RC9: `./bin/sbt2-clean 'core/publishLocal' 'monorepo/publishLocal'`

## v0.12.1

Published: 2026-04-26
GitHub release:
[v0.12.1](https://github.com/scalauser12/sbt-release-io/releases/tag/v0.12.1)

`v0.12.1` is a patch release for both plugins that adds precondition hook factories,
hardens version and tag validation, improves cross-build behavior, and makes release
Git operations more robust.

### Fixes

- Treat whitespace-only version overrides as absent and reject malformed versions without
  surfacing unused suggestions.
- Keep interactive tag preflight handling aligned with the real release flow.
- Align cross-build Scala switching with sbt's project-scoped rules and preserve cross-build
  Scala settings in session `rawAppend`.
- Push recorded release tags atomically so branch and tag publication stays consistent.

### Improvements

- Add `precondition` hook factories for core hooks, monorepo hooks, and resource-aware hooks so
  guard checks can run during `releaseIO check` / `releaseIOMonorepo check`.
- Refine Git process handling and cleanup around single-line command output, descendant process
  tracking, and command-result based error handling.
- Strengthen test support around build-state handling and repository file helpers.
- Refresh `munit` to `1.3.0` and `scalametaParsers` to `4.16.1`.

### Documentation

- Clarify skip-publish behavior and document the new precondition hook factories.
- Refresh the root README, module READMEs, and published walkthrough/getting-started docs to
  reference `0.12.1`.

### Verification

- sbt 1.12.3: `sbt -Dsbt.version=1.12.3 scalafmtCheckAll scalafmtSbtCheck`
- sbt 1.12.3: `sbt -Dsbt.version=1.12.3 test`
- sbt 2.0.0-RC9: `./bin/sbt2-clean test`
- sbt 1.12.3: `sbt -Dsbt.version=1.12.3 'core/publishLocal' 'monorepo/publishLocal'`
- sbt 2.0.0-RC9: `./bin/sbt2-clean 'core/publishLocal' 'monorepo/publishLocal'`

## v0.12.0

Published: 2026-04-23
GitHub release:
[v0.12.0](https://github.com/scalauser12/sbt-release-io/releases/tag/v0.12.0)

`v0.12.0` is a minor release for both plugins that adds clearer tracked hook factories for core
and monorepo customization, improves recovery/preflight behavior around hook-driven state, and
hardens Git branch/tag/process handling in release execution.

### Compatibility notes

- `MonorepoReleasePluginLike` narrows `doMonorepoHelp`, `doMonorepoRelease`, and
  `doMonorepoCheck` from `protected` to `private[monorepo]`; external subclasses that overrode
  or called those methods must move to the remaining supported extension points.

### Fixes

- Harden tracked-branch push target resolution by validating trimmed tracking remotes and merge
  refs, and propagate malformed upstream configuration errors with clearer diagnostics.
- Improve Git process cleanup by tracking transient descendant processes more reliably and keeping
  branch/tag pushes aligned with tracked upstream behavior.
- Stabilize tracked reentry and sbt prompt handling, and keep hook-aware preflight version/tag
  summaries aligned with runtime hook state.
- Tighten monorepo selection, version-file, and publish regression handling so preflight and run
  mode stay closer to the resolved release flow.

### Improvements

- Add tracked, intent-named hook factories across the core and monorepo hook types:
  `sideEffect`, `transform`, and `resumable`.
- Add `ReleaseHookHandle` and `MonorepoHookHandle` auto-import aliases so custom Scala build code
  can refer to tracked hook handles more concisely.
- Deprecate the legacy `.io` and `.action` hook constructors in favor of the tracked hook
  factories, while keeping the release engine on the tracked execution path.
- Refactor command execution, lifecycle compilation, preflight rendering, and shared workflow
  helpers for clearer runtime boundaries across the core, monorepo, and runtime modules.

### Documentation

- Refresh the root README, module READMEs, and published walkthrough/getting-started docs to
  reference `0.12.0`.
- Update the core and monorepo customization guidance and examples to describe the tracked hook
  factories and the current hook/policy customization model more clearly.

### Verification

- sbt 1.12.3: `sbt -Dsbt.version=1.12.3 scalafmtCheckAll scalafmtSbtCheck`
- sbt 1.12.3: `sbt -Dsbt.version=1.12.3 test`
- sbt 2.0.0-RC9: `./bin/sbt2-clean test`
- sbt 1.12.3: `sbt -Dsbt.version=1.12.3 'core/publishLocal' 'monorepo/publishLocal'`
- sbt 2.0.0-RC9: `./bin/sbt2-clean 'core/publishLocal' 'monorepo/publishLocal'`

## v0.11.1

Published: 2026-04-16
GitHub release:
[v0.11.1](https://github.com/scalauser12/sbt-release-io/releases/tag/v0.11.1)

`v0.11.1` is a patch release for both plugins that hardens monorepo preflight/version-file
handling, keeps build packaging and published metadata tidy, and refreshes the published docs to
point at the latest release.

### Fixes

- Validate monorepo version files before write steps so missing or malformed files fail earlier.
- Defer check-mode version and tag summaries when runtime hooks can still change them, and keep
  preflight hook deferral plus monorepo write revalidation aligned with the resolved release
  state.
- Align monorepo `check` mode with hook-aware setup validation so rehearsal output matches the
  real release flow more closely.

### Improvements

- Hide the internal `testkit` module from published metadata while keeping local test wiring
  intact.
- Refactor the build definition around shared helper objects and merge `BuildVersions` into
  `BuildSettings` for simpler build wiring.
- Bundle runtime docs into the core plugin Javadoc and apply formatting cleanup across the build
  definition and related compat sources.

### Documentation

- Refresh monorepo hook/check-mode wording and update published version snippets across the root,
  module, and walkthrough docs to reference `0.11.1`.

### Verification

- sbt 1.12.3: `sbt -Dsbt.version=1.12.3 scalafmtCheckAll scalafmtSbtCheck`
- sbt 1.12.3: `sbt -Dsbt.version=1.12.3 test`
- sbt 2.0.0-RC9: `./bin/sbt2-clean test`
- sbt 1.12.3: `sbt -Dsbt.version=1.12.3 core/scripted`
- sbt 2.0.0-RC9: `./bin/sbt2-clean core/scripted`
- sbt 1.12.3: `sbt -Dsbt.version=1.12.3 monorepo/scripted`
- sbt 2.0.0-RC9: `./bin/sbt2-clean monorepo/scripted`
- sbt 1.12.3: `sbt -Dsbt.version=1.12.3 'core/publishLocal' 'monorepo/publishLocal'`
- sbt 2.0.0-RC9: `./bin/sbt2-clean 'core/publishLocal' 'monorepo/publishLocal'`

## v0.11.0

Published: 2026-04-14
GitHub release:
[v0.11.0](https://github.com/scalauser12/sbt-release-io/releases/tag/v0.11.0)

### Breaking changes

- Remove `ReleaseSharedPlugin` as a public plugin contract and stop publishing the
  `sbt-release-io-shared` support artifact.
- Shared `releaseIO*` keys in `.scala` build code are owned by `ReleasePluginIO.autoImport.*`
  again, and `MonorepoReleasePlugin` once more requires the core plugin surface transitively.

### CI/Build

- Fold the packaged runtime/shared support classes back into `sbt-release-io` and keep
  `monorepo/publishLocal` verifying against `core/publishLocal` on both supported sbt lines.

### Documentation

- Refresh contributor docs and architecture notes to describe the restored `monorepo -> core`
  contract and the removal of the published shared support artifact.

### Verification

- sbt 1.12.3: `sbt -Dsbt.version=1.12.3 scalafmtCheckAll scalafmtSbtCheck`
- sbt 1.12.3: `sbt -Dsbt.version=1.12.3 test`
- sbt 2.0.0-RC9: `./bin/sbt2-clean test`
- sbt 1.12.3: `sbt -Dsbt.version=1.12.3 core/scripted`
- sbt 2.0.0-RC9: `./bin/sbt2-clean core/scripted`
- sbt 1.12.3: `sbt -Dsbt.version=1.12.3 monorepo/scripted`
- sbt 2.0.0-RC9: `./bin/sbt2-clean monorepo/scripted`
- sbt 1.12.3: `sbt -Dsbt.version=1.12.3 'core/publishLocal' 'monorepo/publishLocal'`
- sbt 2.0.0-RC9: `./bin/sbt2-clean 'core/publishLocal' 'monorepo/publishLocal'`

## v0.10.0

Published: 2026-04-10
GitHub release:
[v0.10.0](https://github.com/scalauser12/sbt-release-io/releases/tag/v0.10.0)

`v0.10.0` is a breaking release for both plugins that removes the deprecated compatibility
namespaces and mixin-based grouped-key access, continues the shared runtime cleanup, and refreshes
the published docs around the grouped hook/policy customization model.

### Breaking changes

- Remove the deprecated `ReleaseIO` and `MonorepoReleaseIO` compatibility namespaces. Scala-source
  custom plugins and helpers must use `ReleasePluginIO.autoImport.*` or
  `MonorepoReleasePlugin.autoImport.*` when they need grouped keys from `.scala` sources.
- Stop exposing deprecated mixin-based grouped key access from `ReleasePluginIOLike` and
  `MonorepoReleasePluginLike`; grouped `.sbt` usage and grouped key labels remain unchanged.

### Improvements

- Extract more shared runtime, command, workflow, and packaging support so the core and monorepo
  modules share the same release engine more consistently without changing the supported
  build-facing customization surface.
- Tighten runtime boundary checks, decision-default handling, and release command plumbing while
  restoring core Scaladoc coverage and keeping publish behavior aligned across both sbt lines.

### CI/Build

- Run the sbt 2 scripted CI jobs via `./bin/sbt2-clean` so clean-checkout release verification does
  not depend on local IDE-generated files.

### Documentation

- Refresh the root README, module READMEs, walkthroughs, onboarding guides, recipes, and
  reference docs to reflect the `autoImport.*` migration path, the customization terminology
  update, and clearer change-detection guidance.
- Rename the core customization walkthrough and expand monorepo setup guidance around pinned
  `project/build.properties`, explicit selectors, and cross-build hook inheritance for the
  `publish-artifacts` phase.

### Tests

- Expand architecture-boundary, release-command, publish-smoke, grouped-key compatibility-removal,
  and runtime/workflow coverage across the core and monorepo modules.
- Keep publish-local smoke and tag-default warning coverage aligned with the refactored runtime.

### Verification

- sbt 1.12.3: `sbt scalafmtCheckAll scalafmtSbtCheck`
- sbt 1.12.3: `sbt -Dsbt.version=1.12.3 test`
- sbt 2.0.0-RC9: `./bin/sbt2-clean test`
- sbt 1.12.3: `sbt -Dsbt.version=1.12.3 'core/publishLocal' 'monorepo/publishLocal'`
- sbt 2.0.0-RC9: `./bin/sbt2-clean 'core/publishLocal' 'monorepo/publishLocal'`

## v0.9.2

Published: 2026-04-09
GitHub release:
[v0.9.2](https://github.com/scalauser12/sbt-release-io/releases/tag/v0.9.2)

`v0.9.2` is a patch release for both plugins that makes plugin `autoImport` the canonical
Scala-source import path, keeps the legacy compatibility namespaces available with deprecation
warnings, and refreshes the published examples and docs to match.

### Improvements

- Make `ReleasePluginIO.autoImport.*` and `MonorepoReleasePlugin.autoImport.*` the canonical
  Scala-source imports for grouped public keys while keeping `.sbt` usage unchanged.
- Keep `ReleaseIO` and `MonorepoReleaseIO` available as deprecated compatibility shims that
  forward to the same public keys, preserving source compatibility for existing Scala build
  helpers and custom plugins.
- Extract internal release manifest metadata and monorepo tag-setting helpers without changing the
  supported `.sbt` key surface.

### Documentation

- Refresh customization guides and example plugins to use the canonical plugin `autoImport.*`
  imports and explain the deprecated compatibility namespaces.
- Update the root README, module READMEs, and walkthrough/getting-started docs to reference
  `0.9.2`.

### Tests

- Expand grouped-key compatibility coverage so core and monorepo specs assert that the deprecated
  compatibility namespaces and mixins forward to the canonical plugin `autoImport` keys.
- Keep compatibility specs checking that plugin `autoImport` exposes the intended public project
  keys without leaking internal helpers.

### Verification

- sbt 1.12.3: `sbt scalafmtCheckAll scalafmtSbtCheck`
- sbt 1.12.3: `sbt -Dsbt.version=1.12.3 test`
- sbt 2.0.0-RC9: `./bin/sbt2-clean test`

## v0.9.1

Published: 2026-04-07
GitHub release:
[v0.9.1](https://github.com/scalauser12/sbt-release-io/releases/tag/v0.9.1)

`v0.9.1` is a patch hardening release for both plugins, tightening Git/VCS execution, monorepo
selection and cross-build cleanup, prompt/help behavior, and supporting guidance without expanding
the public customization surface introduced in `v0.9.0`.

### Fixes

- Share Git process execution and status capture across the core and monorepo modules, restore the
  default remote-check timeout fallback, and keep remote/push behavior compatible with existing VCS
  adapter expectations.
- Fix release edge cases around push handling, narrow monorepo project selection, and the sbt 2
  test lane.
- Fix monorepo cross-build validation cleanup and keep override-only selection narrow after
  reverting downstream expansion.

### Improvements

- Avoid per-byte allocation in `PromptAdapter.readLineBlocking` so interactive input stays lighter.
- Extract `CorePreflight.buildSummary` and remove the self-referential hint from `releaseIO help`
  output for cleaner preflight/help messaging.

### Documentation

- Clarify the non-interactive default for sbt-release migrants and expand resource-hook check-mode
  guidance in the core docs.
- Refresh the root README, module READMEs, onboarding guides, and walkthroughs to reference
  `0.9.1`.

### Tests

- Add regression coverage for Git/VCS handling, narrow override-only monorepo selection,
  shared-path change detection, and monorepo cross-build cleanup.

### Verification

- sbt 1.12.3: `sbt scalafmtCheckAll scalafmtSbtCheck`
- sbt 1.12.3: `sbt -Dsbt.version=1.12.3 test`
- sbt 2.0.0-RC9: `./bin/sbt2-clean test`

## v0.9.0

Published: 2026-04-06
GitHub release:
[v0.9.0](https://github.com/scalauser12/sbt-release-io/releases/tag/v0.9.0)

`v0.9.0` completes the breaking API cleanup started in `v0.8.1`, making the grouped hook and
policy settings the only supported build-facing customization model across both plugins while
continuing the internal release-runtime simplification work.

### Breaking changes

- Remove all deprecated flat key aliases from `ReleaseIO` and `MonorepoReleaseIO`; grouped
  `releaseIO*` and `releaseIOMonorepo*` names are now the only supported public settings surface,
  and the canonical sbt key labels were renamed to match those grouped names.
- Remove the lower-level `ReleaseStepIO` and `MonorepoStepIO` DSLs, plus the public
  `ReleaseSteps` and `MonorepoReleaseSteps` facades. Build-facing customization now goes through
  grouped hook settings, grouped policy settings, and resource-hook custom plugins only.
- Retire docs and examples built around step-list editing. Migration guidance now points to
  grouped hook/policy settings and resource-aware hook plugins instead.

### Improvements

- Simplify and consolidate lifecycle compilation, release command handling, preflight plumbing,
  execution helpers, and shared hook descriptor ordering across the core and monorepo modules
  without changing the supported hook-first customization model.
- Continue tightening test support, process/resource handling, and internal type naming so the
  shared runtime stays easier to reason about and maintain.

### Documentation

- Refresh the root README, module READMEs, onboarding guides, and walkthroughs to reference
  `0.9.0`.
- Keep the docs aligned on the supported migration path: grouped hook settings, grouped policy
  settings, and resource-aware custom plugins instead of flat aliases or step-list editing.

### Tests

- Expand and update coverage around grouped public keys, lifecycle compilation, default settings,
  release command handling, monorepo selection/order behavior, and shared test support while
  removing compatibility scaffolding tied to the retired lower-level step DSLs.

### Verification

- sbt 1.12.3: `sbt -Dsbt.version=1.12.3 scalafmtCheckAll scalafmtSbtCheck`
- sbt 1.12.3: `sbt -Dsbt.version=1.12.3 test`
- sbt 2.0.0-RC9: `./bin/sbt2-clean test`

## v0.8.1

Published: 2026-04-03
GitHub release:
[v0.8.1](https://github.com/scalauser12/sbt-release-io/releases/tag/v0.8.1)

`v0.8.1` hardens release execution across both plugins with more reliable
validation and preflight handling, sturdier cross-build and VCS execution, and
clearer build-facing customization guidance.

### Fixes

- Fail check mode earlier and more consistently when validation/preflight
  reports `ctx.failWith(...)`.
- Preserve threaded validation when replacing plain step validation in both the
  core and monorepo step DSLs.
- Fix the core scripted `check` scenario for configured-but-missing version
  files.

### Improvements

- Add grouped setting-key traits for the core and monorepo plugins so build-
  facing settings are organized by behavior, defaults, policy, hooks,
  versioning, and VCS while keeping the existing lower-level surface available
  as deprecated aliases.
- Rework cross-build execution, Scala version switching, and version inquiry
  handling so validation, logging, state restoration, and failure reporting are
  more reliable across both plugins.
- Freeze publish-hook gate decisions at validation time so publish behavior
  remains stable for the rest of the release run.
- Consolidate Git command execution into shared process helpers, improve
  timeout/process cleanup and tag-push validation, and keep custom VCS adapter
  timeout behavior compatible with the existing fallback.
- Improve monorepo change detection, dependency ordering, and resolved-version
  handling with better caching, deterministic project ordering, and clearer
  version state.

### Documentation

- Deprecate the lower-level `ReleaseStepIO` and `MonorepoStepIO` DSLs in docs
  and examples in favor of hooks, policies, and resource-aware customization.
- Update the root README, module READMEs, and getting-started guides to
  reference `0.8.1`.
- Clarify hook behavior for cross-build publish phases and document custom VCS
  timeout responsibilities more explicitly.

### Tests

- Expand coverage around grouped keys, step validation composition, publish-hook
  gating, cross-build execution/restoration, Git process handling, tag pushing,
  version inquiry failures, monorepo change detection, dependency ordering, and
  manifest metadata.
- Add scripted coverage for grouped-key configuration and missing configured
  version-file scenarios, plus resource-hook materialization tests.
- Run the aggregated sbt 2 unit-test lane in a fixed project order so stdin-
  driven prompt tests and shared-state suites stay deterministic.

### Verification

- sbt 1.12.3: `sbt scalafmtCheckAll scalafmtSbtCheck`
- sbt 1.12.3: `sbt test`
- sbt 1.12.3: `sbt core/scripted`
- sbt 1.12.3: `sbt monorepo/scripted`
- sbt 2.0.0-RC9: `./bin/sbt2-clean test`
- sbt 2.0.0-RC9: `./bin/sbt2-clean core/scripted`
- sbt 2.0.0-RC9: `./bin/sbt2-clean monorepo/scripted`

## v0.8.0

Published: 2026-04-01
GitHub release:
[v0.8.0](https://github.com/scalauser12/sbt-release-io/releases/tag/v0.8.0)

`v0.8.0` consolidates the internal release runtime for both plugins, formalizes
persistent decision defaults in the public API, and removes the remaining
legacy raw-process customization surface in favor of hooks, policies, and
resource-aware custom plugins.

### Breaking Changes

- Remove the legacy raw-process customization APIs from the supported public
  surface: `releaseIOProcess`, `releaseIOMonorepoProcess`, and the monorepo
  `insertStepAfter` / `insertStepBefore` helpers.
- Treat hooks, policy keys, and resource-aware custom plugins as the supported
  migration path for advanced release customization in both modules.

### Improvements

- Add shared persistent decision-default settings for tag conflicts and yes/no
  release prompts: `releaseIODefaultsTagExistsAnswer`,
  `releaseIODefaultsSnapshotDependenciesAnswer`,
  `releaseIODefaultsRemoteCheckFailureAnswer`,
  `releaseIODefaultsUpstreamBehindAnswer`, and
  `releaseIODefaultsPushAnswer`.
- Add matching CLI flags for core and monorepo release commands so non-
  interactive and scripted runs can provide the same default answers
  explicitly.
- Preserve late-bound tag/version settings and release-only manifest metadata
  more consistently across core and monorepo release flows.
- Consolidate duplicated command execution, lifecycle compilation, and default-
  setting wiring into shared internal helpers while keeping the public
  hook/policy surface stable.

### Documentation

- Update the root README, module READMEs, and onboarding guides to reference
  `0.8.0`.
- Clarify in the scripted-test READMEs how to run the scripted suites on both
  sbt 1 and sbt 2.
- Keep the core and monorepo docs aligned on hooks, policies, and resource
  hooks as the supported customization surface after the raw-process removal.

### Tests

- Extract shared test harness code into the internal `sbt-release-io-testkit`
  module so `monorepo` no longer depends on `core` test output.
- Expand coverage around manifest metadata, hook compilation, VCS/tag handling,
  publish flow, late-bound setting resolution, cross-build behavior, and
  monorepo change selection.

### CI & Build

- Add the internal unpublished `sbt-release-io-testkit` project to the build
  graph and root aggregation.
- Keep the release publish path tag-driven through GitHub Actions and
  `sbt ci-release`.

### Verification

- sbt 1.12.3: `sbt scalafmtCheckAll scalafmtSbtCheck`
- sbt 1.12.3: `sbt testkit/test core/test monorepo/test`
- sbt 1.12.3: `sbt "core/scripted" "monorepo/scripted"`
- sbt 2.0.0-RC9: `./bin/sbt2-clean testkit/test core/test monorepo/test`
- sbt 2.0.0-RC9: `./bin/sbt2-clean "core/scripted" "monorepo/scripted"`

## v0.7.1

Published: 2026-03-31
GitHub release:
[v0.7.1](https://github.com/scalauser12/sbt-release-io/releases/tag/v0.7.1)

`v0.7.1` hardens the release execution path for both `sbt-release-io` and
`sbt-release-io-monorepo`, with clearer process-mode behavior, stricter
preflight validation, and improved interactive release flows.

### Improvements

- Refactor core and monorepo release execution around dedicated command
  execution, lifecycle, and version-workflow helpers for clearer process-mode
  behavior and release-step composition.
- Harden monorepo preflight, VCS initialization, project selection, tagging,
  and per-project error handling so release runs fail earlier and more clearly.
- Improve interactive release input handling, confirmation prompts, and error
  reporting across the release workflows.
- Strengthen version-writing, tag orchestration, and VCS validation paths in
  both modules while preserving the hook-first customization model introduced
  in `v0.7.0`.

### Documentation

- Clarify release-process behavior in the monorepo concepts, customization, and
  getting-started guides.
- Refresh contributor-facing project guidance in `CLAUDE.md`.

### Tests

- Replace large legacy mode suites with more focused process-mode, release-run,
  compose, cross-build, hook, publish, and change-detection coverage across
  core and monorepo.
- Add targeted coverage for concurrent stdin safety, check-mode output, VCS
  operations, version workflows, publish failure handling, and shared-path
  change detection.

### CI & Build

- Update GitHub Actions for Node 24 readiness.
- Load `sbt-scalafmt` only on sbt 1 so the sbt 2 verification lanes avoid the
  plugin's mixed Scala 3 / 2.13 dependency graph.
- Pin `sbt-scoverage` to `2.4.2`, the latest release published for the sbt 2
  plugin line, so `./bin/sbt2-clean test` resolves a compatible coverage
  plugin.

### Verification

- sbt 1.12.3: `sbt scalafmtCheckAll scalafmtSbtCheck test core/scripted
  monorepo/scripted`
- sbt 2.0.0-RC9: `./bin/sbt2-clean test core/scripted monorepo/scripted`

## v0.7.0

Published: 2026-03-28  
GitHub release:
[v0.7.0](https://github.com/scalauser12/sbt-release-io/releases/tag/v0.7.0)

`v0.7.0` is the first release that makes hook-based customization the preferred
extension model for both `sbt-release-io` and `sbt-release-io-monorepo`, while
keeping raw process editing available as a legacy compatibility mode.

### Breaking Changes

- Remove monorepo global version mode and unified tags. Existing monorepo builds
  should migrate to per-project version files and per-project tag behavior.

### Features

- Add hook-based customization for the core release plugin.
- Add hook-based customization for the monorepo release plugin.

### Improvements

- Refactor monorepo execution around a prepared session and normalized internal
  process model.
- Improve release preflight checks, error reporting, and project-selection
  handling across core and monorepo flows.
- Restore and verify late-bound setting behavior in hook-based and monorepo
  release paths.
- Add local sbt 2 verification support with the `./bin/sbt2-clean` helper for
  IDE-managed checkouts.

### Documentation

- Rewrite core and monorepo customization docs to recommend hooks and
  enable/disable policies first.
- Add migration guidance from raw process surgery to hook and policy
  equivalents.
- Update compiled examples so the recommended examples use hooks, while keeping
  raw-process examples under explicit legacy/advanced labeling.

### Compatibility Notes

- `releaseIOProcess`, `releaseIOMonorepoProcess`, and protected raw process
  overrides remain available in `v0.7.0`, but they now represent legacy mode
  and are formally deprecated.
- `ReleaseStepIO`, `MonorepoStepIO`, and resource-aware custom plugin patterns
  remain available as the advanced escape hatch during the migration window.
- When legacy mode is active, hook and policy compilation is intentionally
  bypassed so existing process customizations keep their current behavior.

### Verification

- sbt 1.12.3: `sbt scalafmtCheckAll scalafmtSbtCheck test core/scripted
  monorepo/scripted`
- sbt 2.0.0-RC9: `./bin/sbt2-clean compile`, `./bin/sbt2-clean test`,
  `./bin/sbt2-clean core/scripted`, `./bin/sbt2-clean monorepo/scripted`

## v0.6.0

Published: 2026-03-23  
GitHub release:
[v0.6.0](https://github.com/scalauser12/sbt-release-io/releases/tag/v0.6.0)

### Breaking Changes

- **Removed remaining factory methods from `ReleaseIO`**.
  `stepTask`, `stepTaskAggregated`, `stepAction`, and
  `stepActionAggregated` were removed. Use `ReleaseStepIO.fromTask`,
  `ReleaseStepIO.fromTaskAggregated`, or the builder API instead.
- **Removed remaining factory methods from `MonorepoReleaseIO`**.
  `globalStep`, `perProjectStep`, `globalStepAction`,
  `perProjectStepAction`, and task variants were removed. Use
  `MonorepoStepIO.global(...)` / `MonorepoStepIO.perProject(...)` builder
  methods instead.
- **Removed `tag-exists-noninteractive` scripted test**.
  Tag-exists behavior is consolidated into `tag-exists-error`.

#### Migration

Before (`v0.5.x`):

```scala
import io.release.ReleaseIO.*
val step = stepTask(myTask)

import io.release.monorepo.MonorepoReleaseIO.*
val mStep = globalStep("name")(ctx => IO.pure(ctx))
```

After (`v0.6.0`):

```scala
val step = ReleaseStepIO.fromTask(myTask)

val mStep = MonorepoStepIO.global("name").execute(ctx => IO.pure(ctx))
```

### Improvements

- Refactor IO execution in release steps to use `IO.blocking` for all sbt
  `State` operations across core and monorepo.
- Extract `PublishValidation` for shared publish-to validation logic in core.
- Extract `SnapshotDependencyTasks` for aggregated vs per-project snapshot
  checking in core.
- Extract `MonorepoCrossBuild` for monorepo cross-build orchestration.
- Extract `MonorepoVcsCommitHelpers` for shared VCS commit logic in monorepo.
- Extract `ReleaseCommandRunner` for the synchronous IO execution boundary in
  core.
- Standardize log prefixes via `ReleaseLogPrefixes` constants.
- Thread internal execution state through `CoreExecutionState` /
  `MonorepoExecutionState` instead of sbt `State` attributes.
- Improve error handling and release clarity in monorepo steps.
- Refactor validation step execution in `ExecutionEngine` and
  `MonorepoComposer`.
- Refactor version file validation in `VcsSteps` and `MonorepoVersionSteps`.

### Documentation

- Update documentation in `ReleaseIO` and `MonorepoReleaseIO` for clarity.
- Add contributing guidelines in `docs/CONTRIBUTING.md`.
- Restructure documentation into `docs/` with per-module guides.

### Tests

- Migrate the test framework from specs2 to MUnit with munit-cats-effect.
- Add `MonorepoPublishStepsSpec`, `MonorepoVersionStepsSpec`, and
  `MonorepoSelectionResolverSpec`.
- Add `PublishValidationSpec`, `PublishStepsSpec`, `TestBuildStateSpec`, and
  `TestSupportSpec`.
- Significantly expand coverage in `ReleaseStepIOSpec`, `VcsOpsSpec`,
  `ChangeDetectionSpec`, `DependencyGraphSpec`, `MonorepoContextSpec`,
  `MonorepoStepIOSpec`, and `MonorepoVcsStepsSpec`.
- Add the `snapshot-deps-test-scope` scripted test for core.
- Update library dependencies in `build.sbt`.

## v0.5.3

Published: 2026-03-20  
GitHub release:
[v0.5.3](https://github.com/scalauser12/sbt-release-io/releases/tag/v0.5.3)

### Bug Fixes

- Fix cross-build leaving the session on the wrong Scala version when
  `scalaVersion` is set only at `ThisBuild` scope in core and monorepo.
- Fix cross-build restoration not being exception-safe in monorepo. Failed
  cross-builds now restore the entry Scala version before propagating the
  error.
- Fix global-version mode not preserving `releaseIOVersioningFile` across sbt
  state reloads during version writes in monorepo.
- Fix global CLI version overrides (`release-version <version>`) not
  force-including projects in detect-changes mode in monorepo.
- Fix `defaultReadVersion` matching version assignments inside multiline block
  comments in core.
- Fix `publishArtifacts` preflight only validating direct aggregates. It now
  validates the full transitive aggregate graph in core.

### Improvements

- Refactor version input handling in `MonorepoVersionSteps` for clarity and
  non-empty checks.

### Tests

- Add `cross-build-restore` to verify Scala version restoration after
  cross-build with `ThisBuild`-only `scalaVersion`.
- Add `global-override-detect-changes` to verify global overrides force all
  projects in detect-changes mode.
- Add `global-version-file-preserve` to verify a custom
  `releaseIOVersioningFile` survives reloads in global mode.
- Add `publish-nested-aggregate` to verify transitive aggregate `publishTo`
  validation.
- Add unit tests for `defaultReadVersion` block comment handling.

## v0.5.2

Published: 2026-03-19  
GitHub release:
[v0.5.2](https://github.com/scalauser12/sbt-release-io/releases/tag/v0.5.2)

### Bug Fixes

- Fix cross-build handling for empty and single-entry `crossScalaVersions` in
  core and monorepo.
- Fix `publish / skip := true` on an aggregate root suppressing child
  publishing.
- Validate CLI version overrides (`release-version`, `next-version`) and reject
  invalid formats.
- Defer `nextVersionFn` evaluation when CLI `next-version` is provided.
- Fix `Version.withoutSnapshot` stripping qualifiers on non-snapshot versions
  such as `1.0.0-RC1`.
- Use atomic `git push --follow-tags` instead of separate branch and tag
  pushes.
- Apply `releaseIOMonorepoDetectionExcludes` to shared-path change
  detection.
- Preserve `failureCause` in `mergeSnapshot` for per-project failure reporting.

### Improvements

- Extract a shared `errorMessage` helper across 11 call sites.
- Replace `foldLeft` + `:+` with `filterA` / `traverse` for O(n) collection
  performance.
- Eliminate the `ValidatedInputs` intermediate type in `MonorepoReleasePlan`.
- Collapse duplicate `createTag` branches in `MonorepoVcsSteps`.
- Extract duplicate test helpers such as `initRepoWithBrokenRemote` and
  `dummyProject`.
- Promote `metadata` / `withMetadata` to the shared `ReleaseCtx` trait.
- Move `globalVersionWrittenKey` from raw sbt `State` to context metadata.
- Remove redundant `applyVersionOverrides` from `buildContext`.

### Tests

- Add `vcs-signoff` to verify `releaseIOVcsSignOff := true`.
- Add `modified-files-fail` to verify a dirty working directory blocks release.
- Add `invalid-version-input` to verify invalid CLI versions are rejected.
- Add `publish-skip-root` to verify child publish still runs when the root is
  skipped.
- Extend `version-bump` coverage with a major bump scenario.

### Documentation

- Document the tag formatter glob contract (`*` must remain literal).
- Document `insertStepAfter` / `insertStepBefore` first-occurrence semantics.
- Add design comments for `tag-release` and `push-changes` validation
  limitations.
- Fix stale ScalaDoc and `ReleasePluginIOLike` requirements documentation.
- Add resource-safe custom plugins to core README features.
- Update the scripted test count in docs.

## v0.5.1

Published: 2026-03-17  
GitHub release:
[v0.5.1](https://github.com/scalauser12/sbt-release-io/releases/tag/v0.5.1)

### Features

- Add customizable monorepo commit messages via
  `releaseIOMonorepoVcsReleaseCommitMessage` and `releaseIOMonorepoVcsNextCommitMessage`.
- Add customizable monorepo tag comments via `releaseIOMonorepoVcsTagComment` and
  `releaseIOMonorepoUnifiedTagComment`.

### Bug Fixes

- Ensure `MonorepoProjectResolver.mergeSnapshot` preserves `failureCause`
  instead of silently dropping failure diagnostics when steps are reordered.

### Improvements

- Remove a dead `import sbt.internal as _` in `MonorepoVcsSteps`.
- Remove `AGENTS.md` from version control.
- Update the GitHub repository description.

## v0.5.0

Published: 2026-03-16  
GitHub release:
[v0.5.0](https://github.com/scalauser12/sbt-release-io/releases/tag/v0.5.0)

### Breaking Changes

- **Removed resource factory methods**.
  Twelve methods were removed from `ReleaseIO` and `MonorepoReleaseIO`. Use the
  builder API instead.
- **Removed `version-file` CLI feature**.
  The `--version-file` argument parser was removed from
  `MonorepoReleasePlugin`.

### Features

- Add the builder API for custom steps on `ReleaseStepIO` and
  `MonorepoStepIO`, including terminal methods like `.execute`,
  `.executeAction`, and `.validateOnly`.
- Add `MonorepoRuntime` for monorepo version handling.
- Add `CoreReleasePlan` and `MonorepoReleasePlan` for clearer internal
  separation.
- Add `ExecutionEngine` and `ExecutionFlags` as internal execution
  infrastructure.

### Improvements

- Refactor `ReleaseComposer` error handling with per-project failure isolation.
- Refactor `LoadCompat` / `LoadCompatBridge` for improved sbt 2 compatibility.
- Refactor Scala 3 compatibility in core components.
- Simplify `_root_` imports using aliases in `MonorepoReleasePlugin`.

### Documentation

- Rewrite both core and monorepo READMEs extensively.
- Add builder API documentation with examples and terminal method
  explanations.
- Add recovery and rollback guidance to the core README.
- Restructure sections for readability and content ordering.

### Tests

- Add `ReleaseStepIOBuilderSpec` for builder API coverage.
- Add builder API tests to `MonorepoStepDefSpec`.
- Add `CoreReleasePlanSpec`, `VcsStepsSpec`, `VersionStepsSpec`,
  `MonorepoVcsStepsSpec`, `MonorepoProjectResolverSpec`, and
  `MonorepoReleasePlanSpec`.
- Add scripted tests for resource-step actions, late-bound settings,
  push-changes, missing version files, and more.

## v0.4.2

Published: 2026-03-09  
GitHub release:
[v0.4.2](https://github.com/scalauser12/sbt-release-io/releases/tag/v0.4.2)

### CI & Build

- Streamline CI workflows and improve sbt version compatibility.
- Update sbt plugins and refine build settings.

### Tests

- Enhance failure validation checks in sbt scripted tests.
- Enhance release checks and validation in sbt scripted tests.

### Documentation

- Update the README to include the new `validate-versions` step.
- Clarify `findStepIndex` documentation around exception handling.

## v0.4.1

Published: 2026-03-07  
GitHub release:
[v0.4.1](https://github.com/scalauser12/sbt-release-io/releases/tag/v0.4.1)

### Bug Fixes

- Fix `onFailure` leakage by stripping `FailureCommand` after release steps
  complete.
- Warn and ignore multiple version arguments for the same project instead of
  silently using the last one.
- Fix the `root-project-sibling-exclusion` scripted test on Linux.

### Improvements

- Store failure causes in `ReleaseContext` and `MonorepoContext` for better
  error diagnostics.
- Add shared-path detection for monorepo change detection via
  `releaseIOMonorepoDetectionSharedPaths`.
- Optimize unified tag mode by pre-computing tag lookups and eliminating
  redundant git calls.

### Documentation

- Enhance README documentation for new release step additions and version
  consistency behavior.
- Improve validation-method documentation in `MonorepoReleasePlugin`.

## v0.4.0

Published: 2026-03-06  
GitHub release:
[v0.4.0](https://github.com/scalauser12/sbt-release-io/releases/tag/v0.4.0)

### Features

- Add `validateVersions` for global version consistency checks before file
  mutation.
- Add `CrossBuildSupport` and `VcsOps` utilities for Scala version management
  and VCS operations.
- Add custom version read and write functions for global version management
  with write-once optimization.
- Store parsed release flags in `State` for easier access during execution
  steps.

### Improvements

- Refactor release context and composer utilities for modularity and error
  handling.
- Refactor error handling and improve IO usage across modules.
- Refactor `topologicalSort` in `DependencyGraph` for clarity and efficiency.
- Restrict `LoadCompat` and `ReleaseKeys` visibility to `private[release]` for
  better encapsulation.

### Documentation

- Update documentation and clarify `autoImport` usage in release plugins.
- Update README files to clarify test commands and settings.

### Tests

- Update test scripts to improve project initialization and structure.
- Add `global-version-custom-write` for custom write-function coverage in
  global version mode.
- Add `global-version-task-mismatch` for version consistency validation.

## v0.3.1

Published: 2026-03-05  
GitHub release:
[v0.3.1](https://github.com/scalauser12/sbt-release-io/releases/tag/v0.3.1)

### Improvements

- Refactor logging in `MonorepoComposer` and `MonorepoPublishSteps` to use
  `IO.blocking` for performance and error handling.
- Add `description` metadata for core and monorepo projects in `build.sbt`.

## v0.3.0

Published: 2026-03-04  
GitHub release:
[v0.3.0](https://github.com/scalauser12/sbt-release-io/releases/tag/v0.3.0)

### Features

- Add `releaseIOMonorepoDetectionExcludes` to filter additional files from
  git-based change detection.
- Add global version override syntax (`release-version 2.0.0`) for uniform
  versions in global-version mode.
- Reject duplicate per-project `release-version` or `next-version` CLI
  overrides.
- Add `releaseIOMonorepoUnifiedTagName` for custom unified tag formatting.

### Bug Fixes

- Keep sbt's in-memory state consistent with the version file on disk by using
  `ThisBuild / version` in global-version mode.
- Expand publish preflight aggregate checks to inspect aggregate sub-modules and
  catch missing `publishTo` before versions and tags are committed.

### Improvements

- Use `NonFatal` consistently in catch blocks across core and monorepo publish
  steps.
- Wrap blocking sbt operations in `IO.blocking`.
- Improve argument validation and error messages in `MonorepoReleasePlugin`.
- Clean up path resolution and error handling in `MonorepoStepHelpers`.
- Remove unused `toReleaseContext` / `fromReleaseContext` conversion methods.

### Tests

- Add core scripted coverage for `cross-build-setting`, `custom-version-format`,
  and `skip-publish-setting`.
- Add monorepo scripted coverage for `cross-build-setting`,
  `custom-unified-tag-name`, `custom-version-format`,
  `global-version-override`, and `skip-tests-setting`.

## v0.2.1

Published: 2026-03-03  
GitHub release:
[v0.2.1](https://github.com/scalauser12/sbt-release-io/releases/tag/v0.2.1)

### Features

- Add support for excluding files from change detection in the monorepo release
  process.
- Add a custom detector test with `baseDir` and `version.sbt` exclusion.
- Add documentation for using Typelevel libraries in release steps.

### Bug Fixes

- Fix SCM URL formatting in `commonSettings`.
- Fix upload archive body reading.

### Improvements

- Refactor verification tasks in monorepo tests to consolidate checks into a
  single `checkAll` task.
- Remove unnecessary dependency on `JvmPlugin` in `ReleasePluginIOLike`.
- Refactor version handling in release context and steps to use dedicated
  methods for release and next versions.
- Remove unused methods for converting to and from `ReleaseContext` in
  `MonorepoContext`.
- Remove `project/metals.sbt` from version control and update `.gitignore`.

### Documentation

- Update the README to clarify release version handling and provide an example
  for uploading compressed artifacts.
- Update the README example for file upload to use gzip compression.
- Clarify custom change detector behavior in the README, including exclusion
  settings.

### CI & Build

- Add a `workflow_dispatch` trigger to the CI workflow.

## v0.2.0

Published: 2026-03-03  
GitHub release:
[v0.2.0](https://github.com/scalauser12/sbt-release-io/releases/tag/v0.2.0)

### Features

- Add scripted tests to automation.

### Improvements

- Change the default monorepo tag separator from hyphen to slash.
- Add `versionScheme` to the build configuration.

### Documentation

- Update plugin version numbers in README files.
- Add a README for monorepo scripted tests.

## v0.1.0

Published: 2026-03-03  
GitHub release:
[v0.1.0](https://github.com/scalauser12/sbt-release-io/releases/tag/v0.1.0)

### Features

- Initial implementation of the `sbt-release-io` plugin wrapping `sbt-release`
  with cats-effect IO.
- Add monorepo release process with per-project failure isolation.
- Add cross-build tests and failure detection for the monorepo release process.
- Add a dynamic project discovery example to the monorepo release plugin.
- Add validation for project selection and version overrides in
  `MonorepoReleasePlugin`.
- Add minimal working setup examples for `CustomStepExamples` and
  `CustomMonorepoStepExamples`.

### Improvements

- Refactor tests and improve logging in monorepo context.
- Refine documentation for `ReleaseContext`, `ReleaseIO`, `ReleaseKeys`, and
  `ReleasePluginIO`.
- Change the project layout.

### Documentation

- Update documentation for custom release plugins with clearer setup
  instructions.
- Enhance documentation in `CustomStepExamples` and
  `CustomMonorepoStepExamples` with usage instructions.
- Update usage instructions for `MonorepoReleasePlugin` to reflect command
  changes.
- Enhance documentation on per-project failure isolation in the monorepo
  release process.

### CI & Build

- Add a git configuration step to the CI workflow for the default branch.
- Add the missing `sbt/setup-sbt` step in CI.
- Add `sonatypeCredentialHost` to the build configuration.
