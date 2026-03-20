# Contributing

Contributions are welcome. Please ensure:

1. All tests pass (`sbt scripted` — covers core and monorepo scripted tests)
2. Code compiles (`sbt compile`)
3. No breaking changes to public API without a clear migration path
4. Add tests for new features

Core scripted tests: `sbt "core/scripted …"`. Monorepo: `sbt "monorepo/scripted …"`.

See `modules/core/src/sbt-test/README.md` and `modules/monorepo/src/sbt-test/README.md` for scripted test layout.
