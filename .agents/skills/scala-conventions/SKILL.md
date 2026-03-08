---
name: scala-conventions
description: Scala/sbt coding conventions for sbt-release-io. Apply when writing or reviewing code.
user-invocable: false
---

## Language & Build

- Scala 2.12 with `scala212source3` dialect
- sbt 1.12.3
- scalafmt 3.10.7, maxColumn 100, `align.preset = most`

## Import Style

- Standard library first, third-party next, project-internal last
- Use wildcard `*` syntax (not `_`): `import sbt.*`, `import sbt.Keys.*`
- Use `as` for aliasing: `import io.release.vcs.Vcs as ReleaseVcs`
- Selective imports when only a few types are needed

## Naming

- Classes/Traits/Objects: PascalCase (`ReleasePluginIO`, `ReleaseStepIO`)
- Methods/vals: camelCase (`fromTask`, `checkCleanWorkingDir`)
- Type parameters: single uppercase letter (`[T]`, `[A]`)
- Setting keys: camelCase (`releaseIOProcess`, `releaseIOCrossBuild`)
- Copy methods prefixed with `with`: `withVersions`, `withVcs`, `withAttr`

## cats-effect IO Patterns

- `IO.blocking { ... }` for all blocking operations (sbt task execution, file I/O, VCS)
- `IO.pure(value)` for already-computed values
- `IO { ... }` for lightweight side effects
- `IO.raiseError(new RuntimeException("message"))` for errors
- `for`-comprehensions for sequential IO composition
- `*>` for sequencing and discarding left result
- `.as(value)` for replacing result
- `.void` for discarding result
- `IO.defer` for lazy evaluation
- `.handleErrorWith` for error recovery
- `unsafeRunSync()` only at plugin entry-point boundaries

## Error Handling

- Use `IO.raiseError` instead of throwing exceptions
- Pattern match with `handleErrorWith` for recovery
- Track failure state via `ctx.copy(failed = true)` / `ctx.fail`
- Never use try-catch in IO code

## Data & Immutability

- All case classes immutable; use `copy()` for modifications
- No `var` in production code
- No `null` values
- Companion objects for factory methods (`ReleaseStepIO.fromTask`, `ReleaseStepIO.fromCommand`)
- Use `lazy val` for expensive initialization

## Functional Patterns

- `foldLeft` with `IO.pure` seed for composing step sequences
- Higher-order functions: pass `ReleaseContext => IO[ReleaseContext]`
- `@scala.annotation.tailrec` for recursive methods
- Pattern matching over `Option` and `List` (avoid `.get`)
- Implicit conversions with `scala.language.implicitConversions`

## sbt Plugin Patterns

- `AutoPlugin` with `override def trigger = allRequirements`
- Export keys via `object autoImport { ... }`
- Use `lazy val projectSettings: Seq[Setting[_]]`
- State threading: `Project.extract(state).runTask(key, state)`
- State attributes: `state.put(key, value)`

## Logging

- Always prefix with `[release-io]`: `state.log.info("[release-io] message")`
- Use string interpolation: `s"[release-io] Variable: $value"`

## Documentation

- ScalaDoc (`/** ... */`) for all public APIs
- Inline comments for complex logic explaining "why"
- Code examples in `{{{ ... }}}` blocks within ScalaDoc

## Testing (specs2)

- Test files: `<Name>Spec.scala`
- Structure: `"Feature" should { "behavior" in { ... } }`
- Nested specs: `"context" >> { "sub-context" >> { ... } }`
- Matchers: `must_==`, `must throwA[Type]`, `must contain(...)`, `must beTrue`
- Helper methods: `private def withContext[A](f: ReleaseContext => A): A`
- `unsafeRunSync()` permitted in test code
