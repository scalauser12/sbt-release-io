package io.release.monorepo

import cats.effect.IO
import cats.effect.Resource
import io.release.TestAssertions.assertFailure
import io.release.TestSupport
import io.release.internal.SbtRuntime
import munit.CatsEffectSuite
import sbt.{internal as _, *}

import java.io.File
import java.lang.reflect.Proxy

class DependencyGraphSpec extends CatsEffectSuite {

  test("DependencyGraph.topologicalSort - return empty sequence for empty project list") {
    TestSupport
      .tempDirResource("dep-graph-empty")
      .evalMap(dir => IO.blocking(TestSupport.dummyState(dir)))
      .use { state =>
        DependencyGraph.topologicalSort(Seq.empty[ProjectRef], state).map { result =>
          assertEquals(result, Seq.empty)
        }
      }
  }

  test(
    "DependencyGraph.topologicalSort - order dependencies before dependents in a diamond graph"
  ) {
    graphStateResource("dep-graph-topological") { dir =>
      val baseBase  = new File(dir, "base")
      val leftBase  = new File(dir, "left")
      val rightBase = new File(dir, "right")
      val topBase   = new File(dir, "top")
      baseBase.mkdirs()
      leftBase.mkdirs()
      rightBase.mkdirs()
      topBase.mkdirs()

      Seq(
        Project("root", dir)
          .aggregate(
            LocalProject("base"),
            LocalProject("left"),
            LocalProject("right"),
            LocalProject("top")
          ),
        Project("base", baseBase),
        Project("left", leftBase).dependsOn(projectDependency("base")),
        Project("right", rightBase).dependsOn(projectDependency("base")),
        Project("top", topBase).dependsOn(projectDependency("left"), projectDependency("right"))
      )
    }.use { case (state, refsById) =>
      val baseRef  = projectRef(refsById, "base")
      val leftRef  = projectRef(refsById, "left")
      val rightRef = projectRef(refsById, "right")
      val topRef   = projectRef(refsById, "top")

      DependencyGraph.topologicalSort(Seq(topRef, rightRef, baseRef, leftRef), state).map {
        ordered =>
          assertEquals(ordered.toSet, Set(baseRef, leftRef, rightRef, topRef))
          assertBefore(ordered, baseRef, leftRef)
          assertBefore(ordered, baseRef, rightRef)
          assertBefore(ordered, leftRef, topRef)
          assertBefore(ordered, rightRef, topRef)
      }
    }
  }

  test("DependencyGraph.topologicalSort - only consider dependencies within the provided subset") {
    graphStateResource("dep-graph-subset") { dir =>
      val baseBase  = new File(dir, "base")
      val leftBase  = new File(dir, "left")
      val rightBase = new File(dir, "right")
      val topBase   = new File(dir, "top")
      baseBase.mkdirs()
      leftBase.mkdirs()
      rightBase.mkdirs()
      topBase.mkdirs()

      Seq(
        Project("root", dir)
          .aggregate(
            LocalProject("base"),
            LocalProject("left"),
            LocalProject("right"),
            LocalProject("top")
          ),
        Project("base", baseBase),
        Project("left", leftBase).dependsOn(projectDependency("base")),
        Project("right", rightBase).dependsOn(projectDependency("base")),
        Project("top", topBase).dependsOn(projectDependency("left"), projectDependency("right"))
      )
    }.use { case (state, refsById) =>
      val leftRef = projectRef(refsById, "left")
      val topRef  = projectRef(refsById, "top")

      DependencyGraph.topologicalSort(Seq(topRef, leftRef), state).map { ordered =>
        assertEquals(ordered, Seq(leftRef, topRef))
      }
    }
  }

  test(
    "DependencyGraph.topologicalSort - handle disconnected components without reporting a cycle"
  ) {
    graphStateResource("dep-graph-disconnected") { dir =>
      val baseBase      = new File(dir, "base")
      val dependentBase = new File(dir, "dependent")
      val isolatedBase  = new File(dir, "isolated")
      baseBase.mkdirs()
      dependentBase.mkdirs()
      isolatedBase.mkdirs()

      Seq(
        Project("root", dir)
          .aggregate(LocalProject("base"), LocalProject("dependent"), LocalProject("isolated")),
        Project("base", baseBase),
        Project("dependent", dependentBase).dependsOn(projectDependency("base")),
        Project("isolated", isolatedBase)
      )
    }.use { case (state, refsById) =>
      val baseRef      = projectRef(refsById, "base")
      val dependentRef = projectRef(refsById, "dependent")
      val isolatedRef  = projectRef(refsById, "isolated")

      DependencyGraph.topologicalSort(Seq(dependentRef, isolatedRef, baseRef), state).map {
        ordered =>
          assertEquals(ordered.toSet, Set(baseRef, dependentRef, isolatedRef))
          assertBefore(ordered, baseRef, dependentRef)
      }
    }
  }

  test("DependencyGraph.dependedOnBy - compute the reverse dependency map within the project set") {
    graphStateResource("dep-graph-reverse") { dir =>
      val baseBase  = new File(dir, "base")
      val leftBase  = new File(dir, "left")
      val rightBase = new File(dir, "right")
      val topBase   = new File(dir, "top")
      baseBase.mkdirs()
      leftBase.mkdirs()
      rightBase.mkdirs()
      topBase.mkdirs()

      Seq(
        Project("root", dir)
          .aggregate(
            LocalProject("base"),
            LocalProject("left"),
            LocalProject("right"),
            LocalProject("top")
          ),
        Project("base", baseBase),
        Project("left", leftBase).dependsOn(projectDependency("base")),
        Project("right", rightBase).dependsOn(projectDependency("base")),
        Project("top", topBase).dependsOn(projectDependency("left"), projectDependency("right"))
      )
    }.use { case (state, refsById) =>
      val baseRef  = projectRef(refsById, "base")
      val leftRef  = projectRef(refsById, "left")
      val rightRef = projectRef(refsById, "right")
      val topRef   = projectRef(refsById, "top")

      DependencyGraph.dependedOnBy(Seq(baseRef, leftRef, rightRef, topRef), state).map {
        reverseGraph =>
          assertEquals(
            reverseGraph,
            Map(
              baseRef  -> Set(leftRef, rightRef),
              leftRef  -> Set(topRef),
              rightRef -> Set(topRef)
            )
          )
      }
    }
  }

  test("DependencyGraph.transitiveDependents - include the full downstream closure") {
    graphStateResource("dep-graph-transitive") { dir =>
      val baseBase  = new File(dir, "base")
      val leftBase  = new File(dir, "left")
      val rightBase = new File(dir, "right")
      val topBase   = new File(dir, "top")
      baseBase.mkdirs()
      leftBase.mkdirs()
      rightBase.mkdirs()
      topBase.mkdirs()

      Seq(
        Project("root", dir)
          .aggregate(
            LocalProject("base"),
            LocalProject("left"),
            LocalProject("right"),
            LocalProject("top")
          ),
        Project("base", baseBase),
        Project("left", leftBase).dependsOn(projectDependency("base")),
        Project("right", rightBase).dependsOn(projectDependency("base")),
        Project("top", topBase).dependsOn(projectDependency("left"), projectDependency("right"))
      )
    }.use { case (state, refsById) =>
      val baseRef  = projectRef(refsById, "base")
      val leftRef  = projectRef(refsById, "left")
      val rightRef = projectRef(refsById, "right")
      val topRef   = projectRef(refsById, "top")

      DependencyGraph.dependedOnBy(Seq(baseRef, leftRef, rightRef, topRef), state).map {
        reverseGraph =>
          assertEquals(
            DependencyGraph.transitiveDependents(Set(baseRef), reverseGraph),
            Set(leftRef, rightRef, topRef)
          )
      }
    }
  }

  test("DependencyGraph.topologicalSort - fail on circular dependencies") {
    TestSupport
      .tempDirResource("dep-graph-cycle")
      .evalMap { dir =>
        IO.blocking {
          val aBase = new File(dir, "a")
          val bBase = new File(dir, "b")
          aBase.mkdirs()
          bBase.mkdirs()

          val acyclicProjects = Seq(
            Project("root", dir).aggregate(LocalProject("a"), LocalProject("b")),
            Project("a", aBase),
            Project("b", bBase)
          )
          val baseState       = TestSupport.loadedState(
            dir,
            acyclicProjects,
            currentProjectId = Some("root")
          )

          injectResolvedProjects(
            baseState,
            Map(
              "a" -> Seq("b"),
              "b" -> Seq("a")
            )
          )
        }
      }
      .use { cycleState =>
        val refsById = SbtRuntime
          .extracted(cycleState)
          .structure
          .allProjectRefs
          .map(ref => ref.project -> ref)
          .toMap
        val aRef     = projectRef(refsById, "a")
        val bRef     = projectRef(refsById, "b")

        assertFailure[IllegalStateException, Seq[ProjectRef]](
          DependencyGraph.topologicalSort(Seq(aRef, bRef), cycleState)
        ) { err =>
          assert(
            err.getMessage.startsWith("Circular dependency detected among monorepo projects: ")
          )
          assert(err.getMessage.contains("a"))
          assert(err.getMessage.contains("b"))
        }
      }
  }

  private def graphStateResource(
      prefix: String
  )(projectsFor: File => Seq[Project]): Resource[IO, (State, Map[String, ProjectRef])] =
    TestSupport.tempDirResource(prefix).evalMap { dir =>
      IO.blocking {
        val state    = TestSupport.loadedState(
          dir,
          projectsFor(dir),
          currentProjectId = Some("root")
        )
        val refsById =
          SbtRuntime.extracted(state).structure.allProjectRefs.map(ref => ref.project -> ref).toMap
        state -> refsById
      }
    }

  private def projectRef(refsById: Map[String, ProjectRef], id: String): ProjectRef =
    refsById.getOrElse(id, fail(s"Expected loaded ProjectRef for '$id'"))

  private def projectDependency(id: String): ClasspathDependency =
    classpathDependency(LocalProject(id))

  private def injectResolvedProjects(
      state: State,
      dependencyMap: Map[String, Seq[String]]
  ): State =
    CycleStatePatcher.inject(state, dependencyMap, projectRef)

  private def patchResolvedProject(
      project: ResolvedProject,
      dependencies: Seq[ProjectRef]
  ): ResolvedProject =
    // DependencyGraph only reads resolved project dependencies, so the proxy overrides
    // that surface and delegates every other method back to the real resolved project.
    Proxy
      .newProxyInstance(
        project.getClass.getClassLoader,
        Array(classOf[ResolvedProject]),
        (proxy, method, args) =>
          if (method.getName == "dependencies")
            dependencies.map(ref => ResolvedClasspathDependency(ref, None)).asInstanceOf[AnyRef]
          else {
            val invokeArgs = if (args == null) Array.empty[AnyRef] else args
            method.invoke(project, invokeArgs*)
          }
      )
      .asInstanceOf[ResolvedProject]

  private def assertBefore(
      ordered: Seq[ProjectRef],
      first: ProjectRef,
      second: ProjectRef
  ): Unit = {
    val firstIndex  = ordered.indexOf(first)
    val secondIndex = ordered.indexOf(second)

    assert(firstIndex >= 0, s"Missing project ${first.project} in $ordered")
    assert(secondIndex >= 0, s"Missing project ${second.project} in $ordered")
    assert(
      firstIndex < secondIndex,
      s"Expected ${first.project} before ${second.project} but got ${ordered.map(_.project)}"
    )
  }

  private object CycleStatePatcher {

    def inject(
        state: State,
        dependencyMap: Map[String, Seq[String]],
        refResolver: (Map[String, ProjectRef], String) => ProjectRef
    ): State = {
      // TestBuildState rejects cyclic project graphs during normal load, so the cycle test
      // patches the already-loaded resolved structure after the fact. This helper is
      // intentionally coupled to sbt internals and should be revisited on sbt upgrades.
      val extracted = SbtRuntime.extracted(state)
      val structure = extracted.structure
      val root      = structure.root
      val refsById  = structure.allProjectRefs.map(ref => ref.project -> ref).toMap

      val loadedUnit       = structure.units(root)
      val resolvedProjects = loadedUnit.defined.map { case (id, project) =>
        id -> dependencyMap
          .get(id)
          .map(depIds => patchResolvedProject(project, depIds.map(id => refResolver(refsById, id))))
          .getOrElse(project)
      }
      val patchedUnit      =
        instantiate(
          target = loadedUnit,
          values = Array[AnyRef](
            invoke0(loadedUnit, "unit"),
            resolvedProjects,
            invoke0(loadedUnit, "rootProjects"),
            invoke0(loadedUnit, "buildSettings")
          ),
          context = "LoadedBuildUnit constructor"
        ).asInstanceOf[sbt.internal.LoadedBuildUnit]
      val patchedUnits     = structure.units.updated(root, patchedUnit)
      val baseArgs         = Array[AnyRef](
        patchedUnits,
        structure.root,
        structure.settings,
        structure.data,
        structure.index,
        structure.streams.asInstanceOf[AnyRef],
        structure.delegates.asInstanceOf[AnyRef],
        structure.scopeLocal.asInstanceOf[AnyRef],
        invoke0(structure, "compiledMap")
      )
      val structureArgs    =
        if (maxConstructor(structure, "BuildStructure").getParameterCount > baseArgs.length)
          baseArgs :+ invoke0(structure, "converter")
        else baseArgs
      val patchedStructure =
        instantiate(
          target = structure,
          values = structureArgs,
          context = "BuildStructure constructor"
        ).asInstanceOf[sbt.internal.BuildStructure]

      Project.setProject(Project.session(state), patchedStructure, state)
    }

    private def invoke0(target: AnyRef, methodName: String): AnyRef =
      try target.getClass.getMethod(methodName).invoke(target)
      catch {
        case err: ReflectiveOperationException =>
          throw new IllegalStateException(
            s"DependencyGraphSpec cycle helper could not call sbt internal method '$methodName'. " +
              "Revisit this test glue for the current sbt version.",
            err
          )
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
            s"DependencyGraphSpec cycle helper could not rebuild $context. " +
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
          s"DependencyGraphSpec cycle helper could not find a constructor for $context."
        )
      constructors.maxBy(_.getParameterCount)
    }
  }
}
