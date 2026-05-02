package io.release.monorepo

import cats.effect.IO
import cats.effect.Resource
import io.release.TestAssertions.assertFailure
import io.release.TestSupport
import io.release.monorepo.internal.*
import io.release.runtime.sbt.SbtRuntime
import munit.CatsEffectSuite
import sbt.ClasspathDependency
import sbt.LocalProject
import sbt.ProjectRef
import sbt.classpathDependency

import java.io.File
import java.lang.reflect.Proxy

class MonorepoSelectionResolverSpec extends CatsEffectSuite {

  test("resolve - preserve live project order for explicit selection") {
    resolverFixtureResource("monorepo-selection-explicit").use { fixture =>
      val ctx  = fixture.context(Seq("consumer", "api"))
      val plan = MonorepoSpecSupport.releasePlan(
        selectionMode = SelectionMode.ExplicitSelection,
        selectedNames = Seq("consumer", "api")
      )

      MonorepoSelectionResolver.resolve(ctx, plan).map { result =>
        assertEquals(result.selectionMode, SelectionMode.ExplicitSelection)
        assertEquals(result.projects.map(_.name), Seq("api", "consumer"))
      }
    }
  }

  test("resolve - avoid tag settings resolution for explicit selection") {
    resolverFixtureResource(prefix = "monorepo-selection-explicit-no-tag-settings").use { fixture =>
      val ctx  = withThrowingTagSettings(
        fixture.context(Seq("consumer", "api")),
        "explicit selection should not resolve tag settings"
      )
      val plan = MonorepoSpecSupport.releasePlan(
        selectionMode = SelectionMode.ExplicitSelection,
        selectedNames = Seq("consumer", "api")
      )

      MonorepoSelectionResolver.resolve(ctx, plan).map { result =>
        assertEquals(result.selectionMode, SelectionMode.ExplicitSelection)
        assertEquals(result.projects.map(_.name), Seq("api", "consumer"))
      }
    }
  }

  test("resolve - return all projects with AllChanged when detectChanges is disabled") {
    resolverFixtureResource(
      prefix = "monorepo-selection-all-changed",
      rootSettings = Seq(
        MonorepoReleasePlugin.autoImport.releaseIOMonorepoDetectionEnabled := false
      )
    ).use { fixture =>
      val ctx = fixture.context(Seq("core", "api", "consumer"))

      MonorepoSelectionResolver
        .resolve(ctx, MonorepoSpecSupport.releasePlan(selectionMode = SelectionMode.DetectChanges))
        .map { result =>
          assertEquals(result.selectionMode, SelectionMode.AllChanged)
          assertEquals(result.projects.map(_.name), Seq("core", "api", "consumer"))
        }
    }
  }

  test("resolve - avoid tag settings resolution when detectChanges is disabled") {
    resolverFixtureResource(
      prefix = "monorepo-selection-all-changed-no-tag-settings",
      rootSettings = Seq(
        MonorepoReleasePlugin.autoImport.releaseIOMonorepoDetectionEnabled := false
      )
    ).use { fixture =>
      val ctx = withThrowingTagSettings(
        fixture.context(Seq("core", "api", "consumer")),
        "disabled detection should not resolve tag settings"
      )

      MonorepoSelectionResolver
        .resolve(ctx, MonorepoSpecSupport.releasePlan(selectionMode = SelectionMode.DetectChanges))
        .map { result =>
          assertEquals(result.selectionMode, SelectionMode.AllChanged)
          assertEquals(result.projects.map(_.name), Seq("core", "api", "consumer"))
        }
    }
  }

  test("resolve - use the custom change detector without downstream expansion") {
    resolverFixtureResource(
      prefix = "monorepo-selection-custom",
      rootSettings = Seq(
        MonorepoReleasePlugin.autoImport.releaseIOMonorepoDetectionChangeDetector    :=
          Some((ref: ProjectRef, _: File, _: sbt.State) => IO.pure(ref.project == "api")),
        MonorepoReleasePlugin.autoImport.releaseIOMonorepoDetectionIncludeDownstream := false
      )
    ).use { fixture =>
      MonorepoSelectionResolver
        .resolve(
          fixture.context(Seq("core", "api", "consumer")),
          MonorepoSpecSupport.releasePlan(selectionMode = SelectionMode.DetectChanges)
        )
        .map { result =>
          assertEquals(result.selectionMode, SelectionMode.DetectChanges)
          assertEquals(result.projects.map(_.name), Seq("api"))
        }
    }
  }

  test("resolve - avoid tag settings resolution when a custom detector is configured") {
    resolverFixtureResource(
      prefix = "monorepo-selection-custom-no-tag-settings",
      rootSettings = Seq(
        MonorepoReleasePlugin.autoImport.releaseIOMonorepoDetectionChangeDetector    :=
          Some((ref: ProjectRef, _: File, _: sbt.State) => IO.pure(ref.project == "api")),
        MonorepoReleasePlugin.autoImport.releaseIOMonorepoDetectionIncludeDownstream := false
      )
    ).use { fixture =>
      val ctx = withThrowingTagSettings(
        fixture.context(Seq("core", "api", "consumer")),
        "custom detector should not resolve tag settings"
      )

      MonorepoSelectionResolver
        .resolve(
          ctx,
          MonorepoSpecSupport.releasePlan(selectionMode = SelectionMode.DetectChanges)
        )
        .map { result =>
          assertEquals(result.selectionMode, SelectionMode.DetectChanges)
          assertEquals(result.projects.map(_.name), Seq("api"))
        }
    }
  }

  test("resolve - expand detected changes to downstream dependents when requested") {
    resolverFixtureResource(
      prefix = "monorepo-selection-downstream",
      rootSettings = Seq(
        MonorepoReleasePlugin.autoImport.releaseIOMonorepoDetectionChangeDetector    :=
          Some((ref: ProjectRef, _: File, _: sbt.State) => IO.pure(ref.project == "api")),
        MonorepoReleasePlugin.autoImport.releaseIOMonorepoDetectionIncludeDownstream := true
      )
    ).use { fixture =>
      MonorepoSelectionResolver
        .resolve(
          fixture.context(Seq("core", "api", "consumer")),
          MonorepoSpecSupport.releasePlan(selectionMode = SelectionMode.DetectChanges)
        )
        .map { result =>
          assertEquals(result.selectionMode, SelectionMode.DetectChanges)
          assertEquals(result.projects.map(_.name), Seq("api", "consumer"))
        }
    }
  }

  test("resolve - force-include unchanged projects that have CLI version overrides") {
    resolverFixtureResource(
      prefix = "monorepo-selection-force-include",
      rootSettings = Seq(
        MonorepoReleasePlugin.autoImport.releaseIOMonorepoDetectionChangeDetector :=
          Some((ref: ProjectRef, _: File, _: sbt.State) => IO.pure(ref.project == "core"))
      )
    ).use { fixture =>
      val plan = MonorepoSpecSupport.releasePlan(
        selectionMode = SelectionMode.DetectChanges,
        releaseVersionOverrides = Map("api" -> "3.0.0"),
        nextVersionOverrides = Map("api" -> "3.1.0-SNAPSHOT")
      )

      MonorepoSelectionResolver.resolve(fixture.context(Seq.empty), plan).map { result =>
        assertEquals(result.projects.map(_.name), Seq("core", "api"))
        assertEquals(
          result.projects.find(_.name == "api").flatMap(_.versions),
          Some("3.0.0" -> "3.1.0-SNAPSHOT")
        )
      }
    }
  }

  test("resolve - keep override-only selection narrow when downstream expansion is enabled") {
    resolverFixtureResource(
      prefix = "monorepo-selection-force-include-downstream-enabled",
      rootSettings = Seq(
        MonorepoReleasePlugin.autoImport.releaseIOMonorepoDetectionChangeDetector    :=
          Some((_: ProjectRef, _: File, _: sbt.State) => IO.pure(false)),
        MonorepoReleasePlugin.autoImport.releaseIOMonorepoDetectionIncludeDownstream := true
      )
    ).use { fixture =>
      val plan = MonorepoSpecSupport.releasePlan(
        selectionMode = SelectionMode.DetectChanges,
        releaseVersionOverrides = Map("api" -> "3.0.0"),
        nextVersionOverrides = Map("api" -> "3.1.0-SNAPSHOT")
      )

      MonorepoSelectionResolver.resolve(fixture.context(Seq.empty), plan).map { result =>
        assertEquals(result.selectionMode, SelectionMode.DetectChanges)
        assertEquals(result.projects.map(_.name), Seq("api"))
        assertEquals(result.projects.exists(_.name == "consumer"), false)
        assertEquals(
          result.projects.find(_.name == "api").flatMap(_.versions),
          Some("3.0.0" -> "3.1.0-SNAPSHOT")
        )
      }
    }
  }

  test("resolve - reject version overrides that target projects not selected for release") {
    resolverFixtureResource("monorepo-selection-unused-overrides").use { fixture =>
      val plan = MonorepoSpecSupport.releasePlan(
        selectionMode = SelectionMode.ExplicitSelection,
        selectedNames = Seq("core"),
        releaseVersionOverrides = Map("api" -> "3.0.0")
      )

      assertFailure[IllegalStateException, MonorepoSelectionResolver.SelectionResult](
        MonorepoSelectionResolver.resolve(fixture.context(Seq.empty), plan)
      )(err =>
        assert(
          err.getMessage.contains("Version overrides target projects not selected for release: api")
        )
      )
    }
  }

  test("resolve - reject unknown selected project names against the live build") {
    resolverFixtureResource("monorepo-selection-unknown-selection").use { fixture =>
      val plan = MonorepoSpecSupport.releasePlan(
        selectionMode = SelectionMode.ExplicitSelection,
        selectedNames = Seq("missing")
      )

      assertFailure[IllegalStateException, MonorepoSelectionResolver.SelectionResult](
        MonorepoSelectionResolver.resolve(fixture.context(Seq.empty), plan)
      )(err => assert(err.getMessage.contains("Unknown projects: missing")))
    }
  }

  test("resolve - reject unknown override project names against the live build") {
    resolverFixtureResource("monorepo-selection-unknown-overrides").use { fixture =>
      val plan = MonorepoSpecSupport.releasePlan(
        selectionMode = SelectionMode.DetectChanges,
        releaseVersionOverrides = Map("missing" -> "1.0.0")
      )

      assertFailure[IllegalStateException, MonorepoSelectionResolver.SelectionResult](
        MonorepoSelectionResolver.resolve(fixture.context(Seq.empty), plan)
      )(err => assert(err.getMessage.contains("Unknown projects in version overrides: missing")))
    }
  }

  test(
    "default - aggregate computation runs when no ThisBuild override is configured"
  ) {
    resolverFixtureResource("monorepo-selection-default-aggregates").use { fixture =>
      MonorepoProjectResolver.resolveAll(fixture.state).map { resolved =>
        assertEquals(resolved.map(_.name).sorted, Seq("api", "consumer", "core"))
      }
    }
  }

  test(
    "default - ThisBuild releaseIOMonorepoSelectionProjects override wins over the plugin's project default"
  ) {
    val overrideSettings: Seq[sbt.Def.Setting[?]] = Seq(
      sbt.ThisBuild / MonorepoReleasePlugin.autoImport.releaseIOMonorepoSelectionProjects := {
        val build = sbt.Keys.loadedBuild.value
        build.allProjectRefs.collect { case (ref, _) if ref.project == "api" => ref }
      }
    )
    resolverFixtureResource(
      "monorepo-selection-thisbuild-override",
      rootSettings = overrideSettings
    ).use { fixture =>
      MonorepoProjectResolver.resolveAll(fixture.state).map { resolved =>
        assertEquals(resolved.map(_.name), Seq("api"))
      }
    }
  }

  test(
    "default - ThisBuild ++= extends the empty plugin base"
  ) {
    // `+=` / `++=` need a ThisBuild base value to append to. The plugin
    // installs `ThisBuild / k := Seq.empty` as that base, so append idioms
    // resolve to the appended elements (not an "undefined setting" error).
    val overrideSettings: Seq[sbt.Def.Setting[?]] = Seq(
      sbt.ThisBuild / MonorepoReleasePlugin.autoImport.releaseIOMonorepoSelectionProjects ++= {
        val build = sbt.Keys.loadedBuild.value
        build.allProjectRefs.collect { case (ref, _) if ref.project == "api" => ref }
      }
    )
    resolverFixtureResource(
      "monorepo-selection-thisbuild-append",
      rootSettings = overrideSettings
    ).use { fixture =>
      MonorepoProjectResolver.resolveAll(fixture.state).map { resolved =>
        assertEquals(resolved.map(_.name), Seq("api"))
      }
    }
  }

  test("validateResolvedProjects - reject duplicate live project names before selection") {
    Resource
      .both(
        TestSupport.tempDirResource("monorepo-selection-duplicate-a"),
        TestSupport.tempDirResource("monorepo-selection-duplicate-b")
      )
      .use { case (dirA, dirB) =>
        IO {
          val duplicateA = ProjectReleaseInfo(
            ref = ProjectRef(dirA.toURI, "shared"),
            name = "shared",
            baseDir = dirA,
            versionFile = new File(dirA, "version.sbt")
          )
          val duplicateB = ProjectReleaseInfo(
            ref = ProjectRef(dirB.toURI, "shared"),
            name = "shared",
            baseDir = dirB,
            versionFile = new File(dirB, "version.sbt")
          )

          MonorepoSelectionResolver
            .validateResolvedProjects(
              Seq(duplicateA, duplicateB),
              MonorepoSpecSupport.releasePlan()
            ) match {
            case Left(message) =>
              assert(message.contains("Duplicate configured monorepo project ids"))
              assert(message.contains("shared"))
              assert(message.contains(dirA.getAbsolutePath))
              assert(message.contains(dirB.getAbsolutePath))
              assert(message.contains("releaseIOMonorepoSelectionProjects"))
            case Right(value)  =>
              fail(s"Expected duplicate project-name validation to fail but got: $value")
          }
        }
      }
  }

  private def resolverFixtureResource(
      prefix: String,
      rootSettings: Seq[sbt.Def.Setting[?]] = Nil
  ): Resource[IO, MonorepoSpecSupport.LoadedFixture] =
    MonorepoSpecSupport.loadedFixtureResource(prefix) { dir =>
      val coreBase     = new File(dir, "core")
      val apiBase      = new File(dir, "api")
      val consumerBase = new File(dir, "consumer")
      coreBase.mkdirs()
      apiBase.mkdirs()
      consumerBase.mkdirs()
      sbt.IO.write(
        new File(dir, "version.sbt"),
        """ThisBuild / version := "0.1.0-SNAPSHOT"""" + "\n"
      )
      sbt.IO.write(new File(coreBase, "version.sbt"), """version := "0.1.0-SNAPSHOT"""" + "\n")
      sbt.IO.write(new File(apiBase, "version.sbt"), """version := "0.1.0-SNAPSHOT"""" + "\n")
      sbt.IO.write(new File(consumerBase, "version.sbt"), """version := "0.1.0-SNAPSHOT"""" + "\n")

      Seq(
        MonorepoSpecSupport.monorepoRootProject(
          dir,
          projectIds = Seq("core", "api", "consumer"),
          settings = rootSettings
        ),
        MonorepoSpecSupport.versionedProject("core", coreBase),
        MonorepoSpecSupport.versionedProject("api", apiBase),
        MonorepoSpecSupport
          .versionedProject("consumer", consumerBase)
          .dependsOn(projectDependency("api"))
      )
    }

  private def projectDependency(id: String): ClasspathDependency =
    classpathDependency(LocalProject(id))

  private def withThrowingTagSettings(ctx: MonorepoContext, message: String): MonorepoContext =
    ctx.withState(TagSettingsStatePatcher.inject(ctx.state, message))

  private object TagSettingsStatePatcher {

    def inject(state: sbt.State, message: String): sbt.State = {
      val extracted     = SbtRuntime.extracted(state)
      val structure     = extracted.structure
      val originalData  = structure.data.asInstanceOf[AnyRef]
      val interfaces    = originalData.getClass.getInterfaces
      val patchedData   =
        Proxy
          .newProxyInstance(
            originalData.getClass.getClassLoader,
            interfaces,
            (_, method, args) =>
              if (shouldThrow(method.getName, args, targetKeyLabel))
                throw new RuntimeException(message)
              else {
                val invokeArgs = if (args == null) Array.empty[AnyRef] else args
                method.invoke(originalData, invokeArgs*)
              }
          )
          .asInstanceOf[AnyRef]
      val baseArgs      = Array[AnyRef](
        structure.units.asInstanceOf[AnyRef],
        structure.root.asInstanceOf[AnyRef],
        structure.settings.asInstanceOf[AnyRef],
        patchedData,
        structure.index.asInstanceOf[AnyRef],
        structure.streams.asInstanceOf[AnyRef],
        structure.delegates.asInstanceOf[AnyRef],
        structure.scopeLocal.asInstanceOf[AnyRef],
        invoke0(structure, "compiledMap")
      )
      val structureArgs =
        if (maxConstructor(structure, "BuildStructure").getParameterCount > baseArgs.length)
          baseArgs :+ invoke0(structure, "converter")
        else baseArgs
      val patched       =
        instantiate(
          target = structure,
          values = structureArgs,
          context = "BuildStructure constructor"
        ).asInstanceOf[sbt.internal.BuildStructure]

      state.put(sbt.Keys.stateBuildStructure, patched)
    }

    private val targetKeyLabel =
      MonorepoReleasePlugin.autoImport.releaseIOMonorepoVcsTagName.key.label

    private def shouldThrow(
        methodName: String,
        args: Array[AnyRef],
        keyLabel: String
    ): Boolean =
      (methodName == "get" || methodName == "getDirect") &&
        Option(args).exists(_.exists(containsTargetKey(_, keyLabel)))

    private def containsTargetKey(value: AnyRef, keyLabel: String): Boolean =
      Option(value).exists { candidate =>
        attributeKeyLabel(candidate).contains(keyLabel) ||
        scopedKeyAttribute(candidate).exists(containsTargetKey(_, keyLabel))
      }

    private def attributeKeyLabel(value: AnyRef): Option[String] =
      invoke0Opt(value, "label").map(_.toString)

    private def scopedKeyAttribute(value: AnyRef): Option[AnyRef] =
      invoke0Opt(value, "key")

    private def invoke0(target: AnyRef, methodName: String): AnyRef =
      try target.getClass.getMethod(methodName).invoke(target)
      catch {
        case err: ReflectiveOperationException =>
          throw new IllegalStateException(
            s"MonorepoSelectionResolverSpec could not call sbt internal method '$methodName'. " +
              "Revisit this test glue for the current sbt version.",
            err
          )
      }

    private def invoke0Opt(target: AnyRef, methodName: String): Option[AnyRef] =
      try Option(target.getClass.getMethod(methodName).invoke(target))
      catch {
        case _: ReflectiveOperationException => None
      }

    private def instantiate(
        target: AnyRef,
        values: Array[AnyRef],
        context: String
    ): AnyRef =
      try
        ReflectionCompat.newInstance(
          maxConstructor(target, context).asInstanceOf[java.lang.reflect.Constructor[AnyRef]],
          values.map(_.asInstanceOf[Object])
        )
      catch {
        case err: ReflectiveOperationException =>
          throw new IllegalStateException(
            s"MonorepoSelectionResolverSpec could not rebuild $context. " +
              "Revisit this test glue for the current sbt version.",
            err
          )
      }

    private def maxConstructor(
        target: AnyRef,
        context: String
    ): java.lang.reflect.Constructor[?] = {
      val constructors = target.getClass.getConstructors
      if (constructors.isEmpty)
        throw new IllegalStateException(
          s"MonorepoSelectionResolverSpec could not find a constructor for $context."
        )
      constructors.maxBy(_.getParameterCount)
    }
  }
}
