# Concepts (monorepo)

## Default release steps

| # | Step | Type | Description |
|---|------|------|-------------|
| 1 | `initialize-vcs` | Global | Detect git, store VCS adapter in context |
| 2 | `check-clean-working-dir` | Global | Validation-only step that fails if uncommitted changes exist |
| 3 | `resolve-release-order` | Global | Topologically sort projects by dependencies |
| 4 | `detect-or-select-projects` | Global | Run change detection or use explicit CLI selection |
| 5 | `check-snapshot-dependencies` | PerProject | Validation-only step that checks SNAPSHOT dependencies and aborts only if the operator or policy declines to continue (checks every Scala version in `crossScalaVersions` when cross-build is enabled) |
| 6 | `inquire-versions` | PerProject | Read current version, compute or prompt for release + next |
| 7 | `run-clean` | PerProject | Clean selected project outputs (see note below) |
| 8 | `run-tests` | PerProject | Run the selected project's `test` task (cross-build enabled, skippable) |
| 9 | `set-release-version` | PerProject | Write release version to `version.sbt` |
| 10 | `commit-release-versions` | Global | Single commit staging all version files |
| 11 | `tag-releases` | PerProject | Create per-project release tags |
| 12 | `publish-artifacts` | PerProject | Publish the selected project's artifacts (cross-build enabled, skippable) |
| 13 | `set-next-version` | PerProject | Write next snapshot version to `version.sbt` |
| 14 | `commit-next-versions` | Global | Single commit staging all version files |
| 15 | `push-changes` | Global | Push branch + tags to tracking remote |

**Global** steps run once. **PerProject** steps run once per selected project in topological order. Only selected projects participate — child projects that weren't selected or discovered by change detection are skipped.

> **Note on `run-clean`:** sbt 2 stays on project-scoped `clean` because `cleanFull` is build-wide and would also clean projects not in the release selection.

## Execution model

### Validate / execute model

1. **Setup segment**: Steps up to and including `detect-or-select-projects`, plus any immediately following `after-selection:*` hooks, run validate-then-execute sequentially. Custom hooks attached here can read state or perform checks before project selection is finalized, and post-selection hooks can still mutate the selected project snapshot before the main segment begins.
2. **Main validation**: Validation of all main-segment steps completes before any main-segment execution starts. So a step's validate cannot observe the effects of an earlier step's execute. The setup segment is the exception — it intentionally interleaves validation and execution per step.
3. **Main execution**: Remaining steps run sequentially, threading `MonorepoContext` through. Task-level failures are detected between steps.

Author implication: if a custom hook needs strict validate -> execute ordering relative to later checks, place it in the setup segment or fold the dependent check and action into the same hook.

### Per-project failure isolation

In a monorepo release, multiple sub-projects run through each **PerProject** step in sequence.
If one project's execute function throws an exception, the plugin **isolates** the failure to that project
so the remaining projects in the same step can still complete.

#### What happens when a per-project execute fails

1. The exception is caught and the error message is logged.
2. The project is marked as **failed** internally.
3. The step **continues** executing for the remaining (non-failed) projects.
4. Once the step finishes, the plugin checks whether any project is marked failed.
   If so, the global release context is marked failed and **all subsequent steps**
   (both Global and PerProject) are skipped entirely.
5. At the end of the release, the overall failure keeps a `MonorepoProjectFailures` cause so the per-project root exceptions remain available for diagnostics. This exception contains a `Seq[MonorepoProjectFailure]`, each with `projectName: String` and `cause: Option[Throwable]`. The type lives under `io.release.monorepo.internal`, so prefer reading the message rather than catching by type.

Given three projects — `core`, `api`, and `web` — with the release steps
`run-tests` → `set-release-version` → `publish-artifacts`:

| Step | core | api | web |
|------|------|-----|-----|
| `run-tests` | passes | **fails** | passes |
| `set-release-version` | skipped | skipped | skipped |
| `publish-artifacts` | skipped | skipped | skipped |

During `run-tests`, `api` throws an exception. The error is logged and `api` is marked failed, but `web` still runs and passes. After `run-tests` completes, the global context is marked failed because `api` failed. All later steps are skipped for every project.

> **Note:** There is no dependency-aware cascade. If `web` depends on `api`, `web` is not automatically marked failed — it continues in the current step.

> **Note:** The isolation model above applies to the **execution** phase only. During the **main validation** segment, a failure in any project's validation immediately stops the entire release — there is no per-project isolation for validation. This fail-fast design ensures that no main-segment mutations begin if any project has a detectable problem after project selection. Setup-segment steps, including hooks before `detect-or-select-projects` and immediate `after-selection:*` hooks, may already have run.

A **Global** step failure immediately marks the context as failed and skips all subsequent steps.

### Topological ordering

Projects are sorted by inter-project dependencies using Kahn's algorithm. Dependencies are always released before dependents.

### Cross-build scope

Cross-build runs per project, not as one global build-wide loop. For a cross-built per-project step, the runtime iterates that selected project across its own `crossScalaVersions` before moving on to the next project in release order.

On every iteration, the Scala-version switch is scoped using sbt's stock `Cross.switchVersion` rule: every project in the loaded build whose `crossScalaVersions` contains the iteration version is aligned to that version. The iterating project's transitive `dependsOn(...)` deps therefore land at the same Scala version automatically — `api`'s 2.13 iteration sees `core` at 2.13 if `core` declares 2.13 in its `crossScalaVersions`, so compile, test, and publish traverse the dep graph at a coherent Scala version without `core` needing to be in the release selection.

Projects without a matching `crossScalaVersions` are deliberately left at their entry version (this is sbt's documented behavior for `+task` against incompatible projects). For monorepos with intentionally misaligned cross sets — e.g. `core` only on 2.12, `api` on both 2.12 and 2.13 — `api`'s 2.13 iteration will compile against whatever Scala version `core` happens to be at, and may fail or silently produce binary-incompatible output. If you hit this, either align `crossScalaVersions` across the dependency chain or split releases to keep each cross-build tree internally consistent.
