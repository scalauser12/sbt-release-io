# sbt-release-io

See the root `CLAUDE.md` for full project documentation, conventions, and architecture.

## Quick Reference

```bash
sbt compile                # compile all modules
sbt test                   # run all unit tests
sbt scripted               # run all scripted integration tests
sbt scalafmtAll            # format sources
sbt scalafmtCheckAll       # check formatting
```

### Cross-build (sbt 2)

```bash
sbt -Dsbt.version=2.0.0-RC9 compile
sbt -Dsbt.version=2.0.0-RC9 test
```
