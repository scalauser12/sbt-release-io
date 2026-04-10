package io.release

import cats.effect.IO
import munit.CatsEffectSuite

import java.nio.file.Files
import java.nio.file.Path
import java.util.regex.Pattern
import scala.collection.mutable.ListBuffer
import scala.util.matching.Regex

class ArchitectureBoundarySpec extends CatsEffectSuite {

  private val repoRoot = TestRepoFiles.resolve("build.sbt").getParent

  private val legacyImportPattern: Regex =
    raw"(?m)^\s*import io\.release\.(internal|steps)\.".r

  private val sharedRuntimeSymbols = Seq(
    "ReleaseCtx",
    "ReleaseCtxOps",
    "ExecutionFlags",
    "ReleaseDecisionDefaults",
    "ReleaseLogPrefixes",
    "ProcessStep",
    "ExecutionEngine",
    "LifecycleCompiler",
    "LifecycleCatalogSupport",
    "CheckModeOutput",
    "HelpDocsLinks",
    "PluginEntrypointSupport",
    "ReleaseCommandRunner",
    "CommandStateSupport",
    "SbtRuntime",
    "PromptAdapter",
    "SnapshotDependencyTasks",
    "DecisionResolver",
    "PublishValidation",
    "VersionWorkflowSupport",
    "DefaultVersionFileIO"
  )

  private def sourceFiles(relativeDir: String): IO[List[Path]] =
    IO.blocking {
      val dir = repoRoot.resolve(relativeDir)

      if (!Files.exists(dir)) Nil
      else {
        val stream = Files.walk(dir)
        try {
          val files = ListBuffer.empty[Path]
          val it    = stream.iterator()

          while (it.hasNext) {
            val path = it.next()
            if (Files.isRegularFile(path) && path.toString.endsWith(".scala")) {
              files += path
            }
          }

          files.toList
        } finally stream.close()
      }
    }

  private def relativePath(path: Path): String =
    repoRoot.relativize(path).toString

  private def symbolDefinitionPattern(symbol: String): Regex =
    (
      raw"(?m)^\s*(?:private\[release\]\s+)?(?:sealed\s+abstract\s+|sealed\s+|final\s+)?(?:case\s+class|class|trait|object)\s+" +
        Pattern.quote(symbol) +
        raw"\b"
    ).r

  private def assertNoLegacyImports(relativeDir: String): IO[Unit] =
    sourceFiles(relativeDir).flatMap { files =>
      IO.blocking {
        val offenders =
          files
            .filter(path => legacyImportPattern.findFirstIn(Files.readString(path)).nonEmpty)
            .map(relativePath)
            .sorted

        assertEquals(offenders, Nil)
      }
    }

  test("core main sources do not import legacy internal or old step facades") {
    assertNoLegacyImports("modules/core/src/main/scala")
  }

  test("monorepo main sources do not import legacy core internals or old step facades") {
    assertNoLegacyImports("modules/monorepo/src/main/scala")
  }

  test("shared runtime kernel types are defined only in modules/runtime") {
    for {
      coreFiles     <- sourceFiles("modules/core/src/main/scala")
      monorepoFiles <- sourceFiles("modules/monorepo/src/main/scala")
      _             <- IO.blocking {
                         val offenders =
                           (coreFiles ++ monorepoFiles).flatMap { path =>
                             val source = Files.readString(path)

                             sharedRuntimeSymbols.collectFirst {
                               case symbol if symbolDefinitionPattern(symbol).findFirstIn(source).nonEmpty =>
                                 s"${relativePath(path)} defines $symbol"
                             }
                           }.sorted

                         assertEquals(offenders, Nil)
                       }
    } yield ()
  }

  test("modules/core no longer carries production sources in io.release.internal") {
    sourceFiles("modules/core/src/main/scala/io/release/internal").flatMap { files =>
      IO.blocking {
        assertEquals(files.map(relativePath).sorted, Nil)
      }
    }
  }
}
