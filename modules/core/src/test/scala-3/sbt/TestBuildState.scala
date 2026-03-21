package sbt

import sbt.internal.*
import sbt.internal.util.Util

/** Test-only adapter over sbt loader internals. Expect to update it when bumping sbt.
  * Behavioral coverage lives in `sbt.TestBuildStateSpec`, and CI compiles/tests this on both
  * sbt lines.
  */
object TestBuildState:

  def apply(
      baseState: State,
      baseDir: File,
      projects: Seq[Project],
      buildSettings: Seq[Setting[?]] = Nil,
      currentProjectId: Option[String] = None
  ): State =
    require(projects.nonEmpty, "Synthetic test states require at least one project.")

    val canonicalBase = baseDir.getCanonicalFile
    val uri           = canonicalBase.toURI
    val rootProjectIds =
      val atBase = projects.filter(_.base.getCanonicalFile == canonicalBase).map(_.id)
      if atBase.nonEmpty then atBase else Seq(projects.head.id)
    val rootProjectId = rootProjectIds.head

    def validateRef(ref: ProjectReference): Unit =
      ref match
        case _: ProjectRef      => ()
        case LocalProject(_)    => ()
        case LocalRootProject   => ()
        case RootProject(`uri`) => ()
        case RootProject(other) =>
          throw new IllegalArgumentException(
            s"Unsupported non-local RootProject reference in synthetic test state: $other"
          )
        case other              =>
          throw new IllegalArgumentException(
            s"Unsupported project reference in synthetic test state: $other"
          )

    projects.foreach { project =>
      project.aggregate.foreach(validateRef)
      project.dependencies.foreach(dep => validateRef(dep.project))
    }

    val preGlobal  = Load.defaultPreGlobal(baseState, canonicalBase, canonicalBase, baseState.log)
    val converter  = preGlobal.converter
    val buildDefBase = new File(canonicalBase, "project")
    val definitions = LoadedDefinitions(
      base = buildDefBase,
      target = Nil,
      loader = getClass.getClassLoader,
      builds = Seq(BuildDef.defaultEmpty),
      projects = projects,
      buildNames = Nil,
      dslDefinitions = DefinedSbtValues.empty
    )
    val plugins = LoadedPlugins(
      base = buildDefBase,
      pluginData = PluginData(Nil, converter),
      loader = getClass.getClassLoader,
      detected = DetectedPlugins(Nil, DetectedModules[BuildDef](Nil))
    )
    val unit = BuildUnit(uri, canonicalBase, definitions, plugins, converter)
    val projectMap = projects.iterator.map(project => project.id -> project).toMap
    val partUnit   = PartBuildUnit(
      unit,
      projectMap,
      rootProjectIds,
      buildSettings
    )
    val partBuild  = PartBuild(
      uri,
      Map(uri -> partUnit),
      converter
    )
    val loaded     = Load.resolveProjects(partBuild)
    val units      = loaded.units
    val delegates  = Util.withCaching(preGlobal.delegates(loaded))
    val scopeLocal: Def.ScopeLocal = preGlobal.scopeLocal
    val inject     = preGlobal.injectSettings.copy(
      global = preGlobal.injectSettings.global ++ Defaults.globalCore
    )
    val settings =
      Load.finalTransforms(
        Load.buildConfigurations(loaded, Load.getRootProject(units), inject)
      )
    val (compiledMap, data) =
      Def.makeWithCompiledMap(settings)(using delegates, scopeLocal, Project.showLoadingKey(loaded))
    val index = Load.structureIndex(data, settings, loaded.extra(data), units)
    val structure = BuildStructure(
      units = units,
      root = uri,
      settings = settings,
      data = data,
      index = index,
      streams = BuildStreams.mkStreams(units, uri, data),
      delegates = delegates,
      scopeLocal = scopeLocal,
      compiledMap = compiledMap,
      converter = converter
    )
    val session0   = Load.initialSession(structure, Load.lazyEval(unit), baseState)
    val session    = session0.copy(
      currentBuild = uri,
      currentProject = Map(uri -> currentProjectId.getOrElse(rootProjectId)),
      original = settings
    )

    Project.setProject(session, structure, baseState)
