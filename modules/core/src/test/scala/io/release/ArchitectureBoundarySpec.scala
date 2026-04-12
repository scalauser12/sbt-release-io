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

  private val coreInternalImportPattern: Regex =
    raw"(?m)^\s*import io\.release\.core\.internal(?:\.|\b)".r

  private val rootReleaseDirectImportPattern: Regex =
    raw"(?m)^\s*import io\.release\.([A-Z][A-Za-z0-9_]*)\b".r

  private val rootReleaseGroupedImportPattern: Regex =
    raw"(?m)^\s*import io\.release\.\{([^}]*)\}".r

  private val sharedRuntimeSymbols = Seq(
    "CleanCompat",
    "CrossBuildSupport",
    "ReleaseCtx",
    "ExecutionFlags",
    "ReleaseDecisionDefaults",
    "ReleaseLogPrefixes",
    "ReleaseSharedKeys",
    "ReleaseSharedDefaultSettingsSupport",
    "ProcessStep",
    "ExecutionEngine",
    "LifecycleCompiler",
    "CheckModeOutput",
    "HelpDocsLinks",
    "PluginEntrypointSupport",
    "ReleaseCommandRunner",
    "LoadCompat",
    "CommandStateSupport",
    "SbtRuntime",
    "PromptAdapter",
    "SnapshotDependencyTasks",
    "DecisionResolver",
    "PublishValidation",
    "VersionWorkflowSupport",
    "DefaultVersionFileIO",
    "ReleaseIOCompat",
    "ReleaseManifestMetadataSupport",
    "VcsOps"
  )

  private val sharedPublicPluginSymbols = Seq(
    "ReleaseSharedPluginAutoImport",
    "ReleaseSharedPlugin"
  )

  private val supportedMainScalaDirs = Seq("scala", "scala-2", "scala-3")

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

  private def moduleMainSourceFiles(moduleRelativeDir: String): IO[List[Path]] =
    supportedMainScalaDirs
      .foldLeft(IO.pure(List.empty[Path])) { (acc, scalaDir) =>
        acc.flatMap(existing =>
          sourceFiles(s"$moduleRelativeDir/src/main/$scalaDir").map(existing ++ _)
        )
      }
      .map(_.distinct)

  private def relativePath(path: Path): String =
    repoRoot.relativize(path).toString

  private def symbolDefinitionPattern(symbol: String): Regex =
    (
      raw"(?m)^\s*(?:private\[release\]\s+)?(?:sealed\s+abstract\s+|sealed\s+|final\s+)?(?:case\s+class|class|trait|object)\s+" +
        Pattern.quote(symbol) +
        raw"\b"
    ).r

  private def importedRootReleaseSymbols(source: String): List[String] = {
    val direct  = rootReleaseDirectImportPattern.findAllMatchIn(source).map(_.group(1))
    val grouped =
      rootReleaseGroupedImportPattern.findAllMatchIn(source).flatMap { groupedImport =>
        groupedImport.group(1).split(",").iterator.flatMap { entry =>
          raw"\b([A-Z][A-Za-z0-9_]*)\b".r.findFirstMatchIn(entry).map(_.group(1))
        }
      }

    (direct ++ grouped).toList
  }

  private def assertNoImports(relativeDir: String, pattern: Regex): IO[Unit] =
    sourceFiles(relativeDir).flatMap { files =>
      IO.blocking {
        val offenders =
          files
            .filter(path => pattern.findFirstIn(Files.readString(path)).nonEmpty)
            .map(relativePath)
            .sorted

        assertEquals(offenders, Nil)
      }
    }

  private def assertNoLegacyImports(relativeDir: String): IO[Unit] =
    assertNoImports(relativeDir, legacyImportPattern)

  private def assertNoImportsInModuleMainSources(
      moduleRelativeDir: String,
      pattern: Regex
  ): IO[Unit] =
    moduleMainSourceFiles(moduleRelativeDir).flatMap { files =>
      IO.blocking {
        val offenders =
          files
            .filter(path => pattern.findFirstIn(Files.readString(path)).nonEmpty)
            .map(relativePath)
            .sorted

        assertEquals(offenders, Nil)
      }
    }

  private def assertNoLegacyImportsInModuleMainSources(moduleRelativeDir: String): IO[Unit] =
    assertNoImportsInModuleMainSources(moduleRelativeDir, legacyImportPattern)

  test("core main sources do not import legacy internal or old step facades") {
    assertNoLegacyImportsInModuleMainSources("modules/core")
  }

  test("monorepo main sources do not import legacy internal or old step facades") {
    assertNoLegacyImportsInModuleMainSources("modules/monorepo")
  }

  test("monorepo main sources do not import core internal packages") {
    assertNoImportsInModuleMainSources("modules/monorepo", coreInternalImportPattern)
  }

  test("monorepo main sources only import ReleasePluginIO from the compatibility bridge") {
    moduleMainSourceFiles("modules/monorepo").flatMap { files =>
      IO.blocking {
        val allowedPluginPath =
          "modules/monorepo/src/main/scala/io/release/monorepo/MonorepoReleasePlugin.scala"
        val offenders         =
          files
            .filter { path =>
              val relPath = relativePath(path)
              importedRootReleaseSymbols(Files.readString(path)).contains("ReleasePluginIO") &&
              relPath != allowedPluginPath
            }
            .map(relativePath)
            .sorted

        assertEquals(offenders, Nil)
      }
    }
  }

  test(
    "monorepo main sources only import runtime-owned io.release symbols plus plugin bridges"
  ) {
    moduleMainSourceFiles("modules/monorepo").flatMap { files =>
      IO.blocking {
        val allowedPluginPath =
          "modules/monorepo/src/main/scala/io/release/monorepo/MonorepoReleasePlugin.scala"
        val runtimeSymbols    = sharedRuntimeSymbols.toSet
        val offenders         =
          files
            .flatMap { path =>
              val relPath = relativePath(path)
              val source  = Files.readString(path)

              importedRootReleaseSymbols(source).flatMap { symbol =>
                val allowed =
                  if (symbol == "ReleaseSharedPlugin" || symbol == "ReleasePluginIO")
                    relPath == allowedPluginPath
                  else runtimeSymbols.contains(symbol)

                if (allowed) None else Some(s"$relPath imports $symbol")
              }
            }
            .distinct
            .sorted

        assertEquals(offenders, Nil)
      }
    }
  }

  test("shared runtime kernel types are defined only in modules/runtime") {
    for {
      sharedFiles   <- moduleMainSourceFiles("modules/shared")
      coreFiles     <- moduleMainSourceFiles("modules/core")
      monorepoFiles <- moduleMainSourceFiles("modules/monorepo")
      _             <- IO.blocking {
                         val offenders =
                           (sharedFiles ++ coreFiles ++ monorepoFiles).flatMap { path =>
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

  test("shared public plugin types are defined only in modules/shared") {
    for {
      runtimeFiles  <- moduleMainSourceFiles("modules/runtime")
      coreFiles     <- moduleMainSourceFiles("modules/core")
      monorepoFiles <- moduleMainSourceFiles("modules/monorepo")
      _             <- IO.blocking {
                         val offenders =
                           (runtimeFiles ++ coreFiles ++ monorepoFiles).flatMap { path =>
                             val source = Files.readString(path)

                             sharedPublicPluginSymbols.collectFirst {
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
