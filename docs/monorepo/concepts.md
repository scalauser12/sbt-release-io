# Concepts (monorepo)

## Default release steps

| # | Step | Type | Description |
|---|------|------|-------------|
| 1 | `initialize-vcs` | Global | Detect git, store VCS adapter in context |
| 2 | `check-clean-working-dir` | Global | Validation-only step that fails if uncommitted changes exist |
| 3 | `resolve-release-order` | Global | Topologically sort projects by dependencies |
| 4 | `detect-or-select-projects` | Global | Run change detection or use explicit CLI selection |
| 5 | `check-snapshot-dependencies` | PerProject | Validation-only step that fails if any SNAPSHOT dependencies are found (cross-build) |
| 6 | `inquire-versions` | PerProject | Read current version, compute or prompt for release + next |
| 7 | `run-clean` | PerProject | Clean selected project outputs; sbt 2 stays on project-scoped `clean` because `cleanFull` is build-wide |
| 8 | `run-tests` | PerProject | Run the selected project's `test` task (cross-build enabled, skippable) |
| 9 | `set-release-version` | PerProject | Write release version to `version.sbt` |
| 10 | `commit-release-versions` | Global | Single commit staging all version files |
| 11 | `tag-releases` | PerProject | Create per-project release tags |
| 12 | `publish-artifacts` | PerProject | Publish the selected project's artifacts (cross-build enabled, skippable) |
| 13 | `set-next-version` | PerProject | Write next snapshot version to `version.sbt` |
| 14 | `commit-next-versions` | Global | Single commit staging all version files |
| 15 | `push-changes` | Global | Push branch + tags to tracking remote |

**Global** steps run once. **PerProject** steps run once per selected project in topological order. Only selected projects participate — child projects that weren't selected or discovered by change detection are skipped.

## Execution model

### Validate / Execute Model

1. **Setup segment**: Steps up to and including `detect-or-select-projects` run validate-then-execute sequentially. Custom steps inserted here can read state or perform checks before project selection is finalized.
2. **Main validation**: Remaining step validation runs against the selected project snapshot produced by setup. In this main segment, later step validation cannot depend on earlier step execution because all main-step validation finishes before any main-step execution begins.
3. **Main execution**: Remaining steps run sequentially, threading `MonorepoContext` through. Task-level failures are detected between steps.

Author implication: if a custom step needs strict validate -> execute ordering relative to later checks, place it in the setup segment or fold the dependent check and action into the same step.

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
5. At the end of the release, the overall failure keeps a `MonorepoProjectFailures` cause so the per-project root exceptions remain available. This exception contains a `Seq[MonorepoProjectFailure]`, each with `projectName: String` and `cause: Option[Throwable]`.

Given three projects — `core`, `api`, and `web` — with the release steps
`run-tests` → `set-release-version` → `publish-artifacts`:

| Step | core | api | web |
|------|------|-----|-----|
| `run-tests` | passes | **fails** | passes |
| `set-release-version` | skipped | skipped | skipped |
| `publish-artifacts` | skipped | skipped | skipped |

During `run-tests`, `api` throws an exception. The error is logged and `api` is marked failed, but `web` still runs and passes. After `run-tests` completes, the global context is marked failed because `api` failed. All later steps are skipped for every project.

> **Note:** There is no dependency-aware cascade. If `web` depends on `api`, `web` is not automatically marked failed — it continues in the current step.

> **Note:** The isolation model above applies to the **execution** phase only. During the **main validation** segment, a failure in any project's validation immediately stops the entire release — there is no per-project isolation for validation. This fail-fast design ensures that no mutations begin if any project has a detectable problem.

A **Global** step failure immediately marks the context as failed and skips all subsequent steps.

### Topological ordering

Projects are sorted by inter-project dependencies using Kahn's algorithm. Dependencies are always released before dependents.

### Cross-build scope

Cross-build runs per project, not as one global build-wide loop. For a cross-built per-project step, the runtime iterates that selected project across its own `crossScalaVersions` before moving on to the next project in release order.
