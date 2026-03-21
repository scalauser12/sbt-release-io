package sbt

import sbt.ProjectExtra.*
import sbt.internal.*
import sbt.internal.inc.MappedFileConverter
import sbt.internal.util.Util
import sbt.nio.{Settings as NioSettings}

/** Test-only adapter over sbt loader internals. Expect to update it when bumping sbt. */
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
    val uri = canonicalBase.toURI
    val rootProjectIds =
      val atBase = projects.filter(_.base.getCanonicalFile == canonicalBase).map(_.id)
      if atBase.nonEmpty then atBase else Seq(projects.head.id)
    val rootProjectId = rootProjectIds.head
    def resolveRef(ref: ProjectReference): Seq[ProjectRef] =
      ref match
        case pr: ProjectRef     => Seq(pr)
        case LocalProject(id)   => Seq(ProjectRef(uri, id))
        case LocalRootProject   => Seq(ProjectRef(uri, rootProjectId))
        case RootProject(`uri`) => Seq(ProjectRef(uri, rootProjectId))
        case RootProject(other) =>
          throw new IllegalArgumentException(
            s"Unsupported non-local RootProject reference in synthetic test state: $other"
          )
        case other              =>
          throw new IllegalArgumentException(
            s"Unsupported project reference in synthetic test state: $other"
          )
    val launcher = baseState.configuration.provider.scalaProvider.launcher
    val rootPaths = Map(
      "OUT" -> canonicalBase.toPath.resolve("target").resolve("out"),
      "BASE" -> canonicalBase.toPath,
      "SBT_BOOT" -> launcher.bootDirectory.toPath,
      "IVY_HOME" -> launcher.ivyHome.toPath,
      "JAVA_HOME" -> Util.javaHome
    )
    val converter = MappedFileConverter(rootPaths, true)
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
    val partUnit = PartBuildUnit(
      unit,
      projectMap,
      rootProjectIds,
      buildSettings
    )
    val loadedUnit = partUnit.resolveRefs(resolveRef)
    val loaded = LoadedBuild(uri, Map(uri -> loadedUnit))
    val units = loaded.units
    val delegates = Util.withCaching(Load.defaultDelegates(loaded))
    val scopeLocal: Def.ScopeLocal =
      key => EvaluateTask.injectStreams(key) ++ NioSettings.inject(key)
    val inject =
      Load.InjectSettings(Load.injectGlobal(baseState) ++ Defaults.globalCore, Nil, _ => Nil)
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
    val session = SessionSettings(
      currentBuild = uri,
      currentProject = Map(uri -> currentProjectId.getOrElse(rootProjectId)),
      original = settings,
      append = Map.empty,
      rawAppend = Nil,
      currentEval = () =>
        throw new IllegalStateException(
          "Synthetic test states do not support compiling session settings from strings."
        )
    )

    Project.setProject(session, structure, baseState)
