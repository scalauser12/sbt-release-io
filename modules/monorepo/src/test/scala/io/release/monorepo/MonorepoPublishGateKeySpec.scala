package io.release.monorepo

import cats.effect.IO
import io.release.TestSupport
import io.release.monorepo.internal.MonorepoLifecycle
import io.release.runtime.sbt.SbtRuntime
import munit.CatsEffectSuite
import sbt.Keys.*
import sbt.{internal as _, *}

import java.io.File

/** Regression coverage for [[MonorepoLifecycle.publishGateKey]]. The cache key has to
  * distinguish cross-build iterations so the frozen publish-skip decision is recomputed
  * each iteration. Scoping the `scalaVersion` lookup to `project.ref` matters because
  * cross-build only switches per-project (not the unscoped `Keys.scalaVersion`, which
  * resolves at sbt's `currentRef` and stays constant across iterations).
  */
class MonorepoPublishGateKeySpec extends CatsEffectSuite {

  test(
    "publishGateKey reflects the project-scoped scalaVersion (so each cross-iteration's frozen decision has its own cache slot)"
  ) {
    val coreScalaA = TestSupport.CurrentScalaVersion
    val coreScalaB = TestSupport.alternateScalaVersion

    MonorepoSpecSupport
      .loadedContextResource("monorepo-publish-gate-key", Seq("core")) { dir =>
        val coreBase = new File(dir, "core")
        coreBase.mkdirs()
        Seq(
          Project("root", dir)
            .aggregate(LocalProject("core"))
            .settings(scalaVersion := coreScalaA),
          Project("core", coreBase).settings(
            scalaVersion           := coreScalaA
          )
        )
      }
      .use { ctx =>
        val coreProject = MonorepoSpecSupport.projectNamed(ctx.projects, "core")
        val coreScope   = Scope(Select(coreProject.ref), Zero, Zero, Zero)

        def installCoreScala(version: String): IO[MonorepoContext] =
          IO.blocking {
            val extracted                                             = SbtRuntime.extracted(ctx.state)
            import extracted.*
            implicit val showKey: sbt.util.Show[sbt.Def.ScopedKey[?]] = extracted.showKey
            val ss: Setting[?]                                        =
              coreScope / scalaVersion := version
            val newSession   = session.appendRaw(Seq(ss))
            val newStructure =
              _root_.io.release.LoadCompat.reapply(newSession.mergeSettings, structure)
            val newState     =
              Project.setProject(newSession, newStructure, ctx.state)
            ctx.withState(newState)
          }

        for {
          ctxA <- installCoreScala(coreScalaA)
          ctxB <- installCoreScala(coreScalaB)
          keyA  = MonorepoLifecycle.publishGateKey(ctxA, coreProject)
          keyB  = MonorepoLifecycle.publishGateKey(ctxB, coreProject)
        } yield {
          assert(keyA.contains(coreScalaA), s"key A should encode core's scalaVersion: $keyA")
          assert(keyB.contains(coreScalaB), s"key B should encode core's scalaVersion: $keyB")
          assertNotEquals(
            keyA,
            keyB,
            "publishGateKey must yield distinct strings when project.ref / scalaVersion differs " +
              "across cross-iterations — without project-scoping the unscoped scalaVersion at " +
              "currentRef (typically root) collapses both iterations onto one cache slot"
          )
        }
      }
  }
}
