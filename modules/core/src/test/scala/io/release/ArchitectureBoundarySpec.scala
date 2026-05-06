package io.release

import cats.effect.IO
import munit.CatsEffectSuite
import scala.collection.concurrent.TrieMap
import scala.collection.mutable.ListBuffer
import scala.meta.*
import scala.meta.inputs.Input
import scala.meta.parsers.*
import scala.meta.parsers.Parsed

import java.nio.file.Files
import java.nio.file.Path
import java.nio.file.Paths

object ArchitectureBoundarySpec {
  sealed trait SourceRootKind

  object SourceRootKind {
    case object Shared extends SourceRootKind
    case object Scala2 extends SourceRootKind
    case object Scala3 extends SourceRootKind
  }

  final case class ParsedFile(
      path: Path,
      relativePath: String,
      rootKind: SourceRootKind,
      source: String,
      tree: Source
  )

  final case class ImportTarget(
      baseSegments: List[String],
      importedSegments: List[String],
      isWildcard: Boolean
  )
}

class ArchitectureBoundarySpec extends CatsEffectSuite {
  import ArchitectureBoundarySpec.*

  private val repoRoot = TestRepoFiles.resolve("build.sbt").getParent

  private val ioReleasePrefix       = List("io", "release")
  private val legacyPackagePrefixes =
    List(
      ioReleasePrefix :+ "internal",
      ioReleasePrefix :+ "steps"
    )
  private val coreInternalPrefix    = List("io", "release", "core", "internal")
  private val monorepoBridgePath    =
    "modules/monorepo/src/main/scala/io/release/monorepo/MonorepoReleasePlugin.scala"

  private val sharedRuntimeSymbols = Seq(
    "CleanCompat",
    "CrossBuildSupport",
    "ReleaseCtx",
    "TrackedContextHandle",
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
    "PluginEntrypoint",
    "ReleaseCommandRunner",
    "ReleaseCommandCli",
    "LoadCompat",
    "SbtRuntime",
    "PromptAdapter",
    "SnapshotDependencyTasks",
    "DecisionResolver",
    "PublishValidation",
    "VersionWorkflow",
    "DefaultVersionFileIO",
    "ReleaseIOCompat",
    "ReleaseManifestMetadata",
    "VcsOps"
  )

  private val monorepoAllowedRootSymbols = sharedRuntimeSymbols.toSet
  private val supportedMainScalaDirs     = Seq("scala", "scala-2", "scala-3")
  private val parsedFilesCache           = TrieMap.empty[String, List[ParsedFile]]

  private def sourceFilesUnsafe(relativeDir: String): List[Path] = {
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

  private def sourceFiles(relativeDir: String): IO[List[Path]] =
    IO.blocking(sourceFilesUnsafe(relativeDir))

  private def relativePath(path: Path): String =
    repoRoot.relativize(path).toString

  private def sourceRootKind(relativePath: String): SourceRootKind =
    if (relativePath.replace('\\', '/').contains("/src/main/scala-2/")) SourceRootKind.Scala2
    else if (relativePath.replace('\\', '/').contains("/src/main/scala-3/"))
      SourceRootKind.Scala3
    else SourceRootKind.Shared

  private def parseWithDialect(
      input: Input.VirtualFile,
      rootKind: SourceRootKind
  ): Parsed[Source] =
    rootKind match {
      case SourceRootKind.Scala2                         =>
        implicit val dialect: Dialect = dialects.Scala212Source3
        input.parse[Source]
      case SourceRootKind.Shared | SourceRootKind.Scala3 =>
        // Shared sources are parsed for import/definition shape only; actual
        // cross-compilation compatibility is still enforced by the normal build lanes.
        implicit val dialect: Dialect = dialects.Scala3
        input.parse[Source]
    }

  private def parseSource(
      path: Path,
      relativePath: String,
      source: String,
      rootKind: SourceRootKind
  ): ParsedFile = {
    val input = Input.VirtualFile(relativePath, source)
    val tree  = parseWithDialect(input, rootKind) match {
      case Parsed.Success(tree) =>
        tree
      case error: Parsed.Error  =>
        fail(
          s"Failed to parse $relativePath: ${error.message} at " +
            s"${error.pos.startLine + 1}:${error.pos.startColumn + 1}"
        )
    }

    ParsedFile(path, relativePath, rootKind, source, tree)
  }

  private def parseSourceFile(path: Path): ParsedFile = {
    val relPath  = relativePath(path)
    val source   = Files.readString(path)
    val rootKind = sourceRootKind(relPath)

    parseSource(path, relPath, source, rootKind)
  }

  private def parseFixtureSource(
      relativePath: String,
      source: String,
      rootKind: SourceRootKind = SourceRootKind.Shared
  ): ParsedFile =
    parseSource(Paths.get(relativePath), relativePath, source, rootKind)

  private def parseModuleMainSourceFiles(moduleRelativeDir: String): IO[List[ParsedFile]] =
    IO.blocking {
      parsedFilesCache.getOrElseUpdate(
        moduleRelativeDir,
        supportedMainScalaDirs
          .flatMap(scalaDir => sourceFilesUnsafe(s"$moduleRelativeDir/src/main/$scalaDir"))
          .map(parseSourceFile)
          .toList
      )
    }

  private def normalizeSegments(segments: List[String]): List[String] =
    segments.dropWhile(_ == "_root_")

  private def refSegments(ref: Term.Ref): List[String] =
    normalizeSegments {
      def loop(current: Term.Ref): List[String] =
        current match {
          case name: Term.Name     =>
            List(name.value)
          case select: Term.Select =>
            select.qual match {
              case qual: Term.Ref => loop(qual) :+ select.name.value
              case _              => List(select.name.value)
            }
          case _                   =>
            Nil
        }

      loop(ref)
    }

  private def typeSegments(tpe: Type): List[String] =
    normalizeSegments {
      def loop(current: Type): List[String] =
        current match {
          case name: Type.Name               =>
            List(name.value)
          case select: Type.Select           =>
            select.qual match {
              case qual: Type     =>
                loop(qual) :+ select.name.value
              case qual: Term.Ref =>
                refSegments(qual) :+ select.name.value
            }
          case Type.Singleton(ref: Term.Ref) =>
            refSegments(ref)
          case _                             =>
            Nil
        }

      loop(tpe)
    }

  private def importTargetsFromImporter(importer: Importer): List[ImportTarget] = {
    val baseSegments = refSegments(importer.ref)

    importer.importees.flatMap {
      case Importee.Name(name)      =>
        List(ImportTarget(baseSegments, baseSegments :+ name.value, isWildcard = false))
      case Importee.Rename(name, _) =>
        List(ImportTarget(baseSegments, baseSegments :+ name.value, isWildcard = false))
      case Importee.Given(tpe)      =>
        List(ImportTarget(baseSegments, baseSegments ++ typeSegments(tpe), isWildcard = false))
      case Importee.GivenAll()      =>
        List(ImportTarget(baseSegments, baseSegments, isWildcard = true))
      case Importee.Wildcard()      =>
        List(ImportTarget(baseSegments, baseSegments, isWildcard = true))
      case _                        =>
        Nil
    }
  }

  private def collectImports(tree: Tree): List[Import] =
    tree match {
      case imp: Import =>
        List(imp)
      case _           =>
        tree.children.flatMap(collectImports)
    }

  private def importTargets(parsed: ParsedFile): List[ImportTarget] =
    collectImports(parsed.tree).flatMap(importValue =>
      importValue.importers.toList.flatMap(importTargetsFromImporter)
    )

  private def rootImportedSymbols(parsed: ParsedFile): Set[String] =
    importTargets(parsed).flatMap { case ImportTarget(_, importedSegments, _) =>
      importedSegments match {
        case "io" :: "release" :: symbol :: _ if symbol.headOption.exists(_.isUpper) =>
          Some(symbol)
        case _                                                                       =>
          None
      }
    }.toSet

  private def hasRootWildcardImport(parsed: ParsedFile): Boolean =
    importTargets(parsed).exists(target =>
      target.isWildcard && target.baseSegments == ioReleasePrefix
    )

  private def importsPackagePrefix(
      parsed: ParsedFile,
      prefixSegments: List[String]
  ): Boolean =
    importTargets(parsed).exists(target =>
      target.baseSegments.startsWith(prefixSegments) ||
        target.importedSegments.startsWith(prefixSegments)
    )

  private def topLevelDefinitionNames(parsed: ParsedFile): List[String] = {
    def fromStats(stats: List[Stat]): List[String] =
      stats.flatMap {
        case pkg: Pkg           => fromStats(pkg.body.stats)
        case cls: Defn.Class    => List(cls.name.value)
        case trt: Defn.Trait    => List(trt.name.value)
        case obj: Defn.Object   => List(obj.name.value)
        case enumDef: Defn.Enum => List(enumDef.name.value)
        case _                  => Nil
      }

    fromStats(parsed.tree.stats.toList)
  }

  private def definesTopLevelSymbol(parsed: ParsedFile, symbol: String): Boolean =
    topLevelDefinitionNames(parsed).contains(symbol)

  private def assertNoPackagePrefixImports(
      moduleRelativeDir: String,
      prefixes: Seq[List[String]]
  ): IO[Unit] =
    parseModuleMainSourceFiles(moduleRelativeDir).flatMap { files =>
      IO {
        val offenders =
          files
            .filter(parsed => prefixes.exists(prefix => importsPackagePrefix(parsed, prefix)))
            .map(_.relativePath)
            .sorted

        assertEquals(offenders, Nil)
      }
    }

  test("rootImportedSymbols handles grouped root imports") {
    val parsed = parseFixtureSource(
      "fixtures/grouped-root-import.scala",
      "import io.release.{ReleaseSharedKeys, VcsOps}\nobject Sample"
    )

    assertEquals(rootImportedSymbols(parsed), Set("ReleaseSharedKeys", "VcsOps"))
    assert(!hasRootWildcardImport(parsed))
  }

  test("rootImportedSymbols handles rooted renamed imports") {
    val parsed = parseFixtureSource(
      "fixtures/rooted-rename.scala",
      "import _root_.io.release.{ReleasePluginIO as CorePlugin}\nobject Sample"
    )

    assertEquals(rootImportedSymbols(parsed), Set("ReleasePluginIO"))
    assert(!hasRootWildcardImport(parsed))
  }

  test("rootImportedSymbols handles member imports") {
    val parsed = parseFixtureSource(
      "fixtures/member-import.scala",
      "import io.release.ReleaseSharedKeys.releaseIOVcsSign\nobject Sample"
    )

    assertEquals(rootImportedSymbols(parsed), Set("ReleaseSharedKeys"))
    assert(!hasRootWildcardImport(parsed))
  }

  test("sourceRootKind handles Windows-style split-source paths") {
    val path = "modules\\core\\src\\main\\scala-2\\Example.scala"

    assertEquals(sourceRootKind(path), SourceRootKind.Scala2)
  }

  test("rootImportedSymbols handles Scala 3 given imports") {
    val parsed = parseFixtureSource(
      "fixtures/given-import.scala",
      "import io.release.given ReleasePluginIO\nobject Sample",
      rootKind = SourceRootKind.Scala3
    )

    assertEquals(rootImportedSymbols(parsed), Set("ReleasePluginIO"))
    assert(!hasRootWildcardImport(parsed))
  }

  test("rootImportedSymbols handles Scala 3 singleton given imports") {
    val parsed = parseFixtureSource(
      "fixtures/given-singleton-import.scala",
      "import io.release.given ReleasePluginIO.type\nobject Sample",
      rootKind = SourceRootKind.Scala3
    )

    assertEquals(rootImportedSymbols(parsed), Set("ReleasePluginIO"))
    assert(!hasRootWildcardImport(parsed))
  }

  test("root wildcard imports include Scala 3 root given imports") {
    val parsed = parseFixtureSource(
      "fixtures/root-given-import.scala",
      "import io.release.given\nobject Sample",
      rootKind = SourceRootKind.Scala3
    )

    assert(hasRootWildcardImport(parsed))
    assertEquals(rootImportedSymbols(parsed), Set.empty[String])
  }

  test("nested given-all imports do not count as root wildcard imports") {
    val parsed = parseFixtureSource(
      "fixtures/nested-given-all-import.scala",
      "import io.release.ReleasePluginIO.given\nobject Sample",
      rootKind = SourceRootKind.Scala3
    )

    assertEquals(rootImportedSymbols(parsed), Set("ReleasePluginIO"))
    assert(!hasRootWildcardImport(parsed))
  }

  test("root wildcard imports are rejected") {
    val direct = parseFixtureSource(
      "fixtures/direct-root-wildcard.scala",
      "import io.release.*\nobject Sample"
    )
    val mixed  = parseFixtureSource(
      "fixtures/grouped-root-wildcard.scala",
      "import io.release.{ReleaseSharedKeys, *}\nobject Sample"
    )

    assert(hasRootWildcardImport(direct))
    assertEquals(rootImportedSymbols(direct), Set.empty[String])

    assert(hasRootWildcardImport(mixed))
    assertEquals(rootImportedSymbols(mixed), Set("ReleaseSharedKeys"))
  }

  test("core main sources do not import legacy internal or old step facades") {
    assertNoPackagePrefixImports("modules/core", legacyPackagePrefixes)
  }

  test("monorepo main sources do not import legacy internal or old step facades") {
    assertNoPackagePrefixImports("modules/monorepo", legacyPackagePrefixes)
  }

  test("monorepo main sources do not import core internal packages") {
    assertNoPackagePrefixImports("modules/monorepo", Seq(coreInternalPrefix))
  }

  test("monorepo main sources only import supported io.release entrypoints") {
    parseModuleMainSourceFiles("modules/monorepo").flatMap { files =>
      IO {
        val offenders =
          files
            .flatMap { parsed =>
              val rootWildcardOffender =
                if (hasRootWildcardImport(parsed))
                  List(s"${parsed.relativePath} imports io.release.*")
                else Nil

              val rootSymbolOffenders =
                rootImportedSymbols(parsed).toList.sorted.flatMap { symbol =>
                  val allowed =
                    if (symbol == "ReleasePluginIO") parsed.relativePath == monorepoBridgePath
                    else monorepoAllowedRootSymbols.contains(symbol)

                  if (allowed) Nil else List(s"${parsed.relativePath} imports $symbol")
                }

              rootWildcardOffender ++ rootSymbolOffenders
            }
            .distinct
            .sorted

        assertEquals(offenders, Nil)
      }
    }
  }

  test("shared runtime kernel types are defined only in modules/runtime") {
    for {
      coreFiles     <- parseModuleMainSourceFiles("modules/core")
      monorepoFiles <- parseModuleMainSourceFiles("modules/monorepo")
      _             <- IO {
                         val offenders = (coreFiles ++ monorepoFiles).flatMap { parsed =>
                           sharedRuntimeSymbols.collectFirst {
                             case symbol if definesTopLevelSymbol(parsed, symbol) =>
                               s"${parsed.relativePath} defines $symbol"
                           }
                         }.sorted

                         assertEquals(offenders, Nil)
                       }
    } yield ()
  }

  test("modules/core no longer carries production sources in io.release.internal") {
    sourceFiles("modules/core/src/main/scala/io/release/internal").flatMap { files =>
      IO {
        assertEquals(files.map(relativePath).sorted, Nil)
      }
    }
  }
}
