package io.release

import cats.effect.unsafe.implicits.global
import io.release.steps.ReleaseSteps
import sbt.*
import sbt.Keys.*
import sbt.complete.DefaultParsers.*
import sbt.complete.Parser

object ReleaseIOPlugin extends AutoPlugin {

  override def trigger = allRequirements

  /** Parse results for command-line arguments. */
  private sealed trait ReleaseArg
  private object ReleaseArg {
    case object WithDefaults extends ReleaseArg
    case object SkipTests extends ReleaseArg
    case object CrossBuild extends ReleaseArg
    case class ReleaseVersion(value: String) extends ReleaseArg
    case class NextVersion(value: String) extends ReleaseArg
    case class TagDefault(value: String) extends ReleaseArg
  }

  /** Parser for releaseIO command arguments. */
  private lazy val releaseParser: Parser[Seq[ReleaseArg]] = {
    import ReleaseArg._

    val withDefaults: Parser[ReleaseArg] = token("with-defaults").map(_ => WithDefaults)
    val skipTests: Parser[ReleaseArg] = token("skip-tests").map(_ => SkipTests)
    val crossBuild: Parser[ReleaseArg] = token("cross").map(_ => CrossBuild)
    val releaseVersion: Parser[ReleaseArg] =
      (token("release-version") ~> Space ~> token(NotSpace, "<release version>"))
        .map(ReleaseVersion)
    val nextVersion: Parser[ReleaseArg] =
      (token("next-version") ~> Space ~> token(NotSpace, "<next version>")).map(NextVersion)
    val tagDefault: Parser[ReleaseArg] =
      (token("default-tag-exists-answer") ~> Space ~> token(NotSpace, "o|k|a|<tag-name>"))
        .map(TagDefault)

    val arg = withDefaults | skipTests | crossBuild | releaseVersion | nextVersion | tagDefault
    (Space ~> arg).*
  }

  object autoImport {
    val releaseIOProcess =
      settingKey[Seq[ReleaseStepIO]]("The sequence of IO release steps to execute")
    val releaseIOCrossBuild = settingKey[Boolean]("Whether to enable cross-building during release")
    val releaseIOSkipPublish = settingKey[Boolean]("Whether to skip publish during release")

    // Re-export factory methods as def wrappers to preserve generic type parameters
    // and default argument values (eta-expanded vals lose both).
    def releaseIOStepTask[T](key: TaskKey[T], enableCrossBuild: Boolean = false): ReleaseStepIO =
      ReleaseStepIO.fromTask(key, enableCrossBuild)
    def releaseIOStepTaskAggregated[T](key: TaskKey[T], enableCrossBuild: Boolean = false): ReleaseStepIO =
      ReleaseStepIO.fromTaskAggregated(key, enableCrossBuild)
    def releaseIOStepCommand(command: String): ReleaseStepIO =
      ReleaseStepIO.fromCommand(command)
    def releaseIOStepCommandAndRemaining(command: String): ReleaseStepIO =
      ReleaseStepIO.fromCommandAndRemaining(command)

    // Re-export sbt-release compatibility conversions
    implicit val sbtReleaseStepConversion
        : sbtrelease.ReleasePlugin.autoImport.ReleaseStep => ReleaseStepIO =
      SbtReleaseCompat.releaseStepToReleaseStepIO

    implicit val sbtReleaseStateTransformConversion: (State => State) => ReleaseStepIO =
      SbtReleaseCompat.stateTransformToReleaseStepIO
  }

  import autoImport._

  override lazy val projectSettings: Seq[Setting[_]] = Seq(
    releaseIOProcess := ReleaseSteps.defaults,
    releaseIOCrossBuild := false,
    releaseIOSkipPublish := false,
    commands += Command("releaseIO")(_ => releaseParser)(doReleaseIO)
  )

  private def doReleaseIO(state: State, args: Seq[ReleaseArg]): State = {
    import ReleaseArg._

    val extracted = Project.extract(state)
    val steps = extracted.get(releaseIOProcess)
    val crossBuildSetting = extracted.get(releaseIOCrossBuild)
    val skipPublish = extracted.get(releaseIOSkipPublish)

    // Parse command-line arguments
    val useDefaults = args.contains(WithDefaults)
    val skipTests = args.contains(SkipTests)
    val crossFromArgs = args.contains(CrossBuild)
    val crossEnabled = crossBuildSetting || crossFromArgs

    val releaseVersionArg = args.collectFirst { case ReleaseVersion(v) => v }
    val nextVersionArg = args.collectFirst { case NextVersion(v) => v }
    val tagDefaultArg = args.collectFirst { case TagDefault(v) => v }

    // Store parsed arguments in State attributes
    val decoratedState = state
      .put(ReleaseKeys.useDefaults, useDefaults)
      .put(ReleaseKeys.skipTests, skipTests)
      .put(ReleaseKeys.cross, crossEnabled)
      .put(ReleaseKeys.commandLineReleaseVersion, releaseVersionArg)
      .put(ReleaseKeys.commandLineNextVersion, nextVersionArg)
      .put(ReleaseKeys.tagDefault, tagDefaultArg)

    val initialCtx = ReleaseContext(
      state = decoratedState,
      skipTests = skipTests,
      skipPublish = skipPublish
    )

    state.log.info("[release-io] Starting release process...")
    state.log.info(s"[release-io] ${steps.length} steps to execute")
    if (crossEnabled) {
      state.log.info("[release-io] Cross-build enabled")
    }

    val finalCtx = ReleaseStepIO.compose(steps, crossEnabled)(initialCtx).unsafeRunSync()

    state.log.info("[release-io] Release completed successfully!")
    finalCtx.state
  }
}
