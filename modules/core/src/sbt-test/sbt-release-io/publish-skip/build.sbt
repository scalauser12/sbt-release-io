name         := "publish-skip-test"
scalaVersion := "2.12.21"

// No publishTo configured, but publish/skip bypasses the check
publish / skip := true

releaseIOVcsIgnoreUntrackedFiles := true

releaseIOPolicyEnablePush := false
