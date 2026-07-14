# sbt-release-io

See the root `CLAUDE.md` for full project documentation, conventions, and architecture.

## Quick Reference

```bash
./bin/sbt2-clean compile        # compile all modules on the default sbt 2 lane
./bin/sbt2-clean test           # run all unit tests
./bin/sbt2-clean scripted       # run all scripted integration tests
sbt scalafmtAll            # format sources
sbt scalafmtCheckAll       # check formatting
```

### Compatibility lane (sbt 1)

```bash
sbt --server --sbt-version 1.12.3 compile
sbt --server --sbt-version 1.12.3 test
```
