package io.release.monorepo

import cats.effect.testing.specs2.CatsEffect
import cats.effect.{IO, Ref, Resource}
import org.specs2.mutable.Specification
import sbt.internal.util.{AttributeMap, ConsoleOut, GlobalLogging, MainAppender}
import sbt.{ProjectRef, State}
import sbtrelease.Compat
import xsbti.*

import java.io.File
import java.nio.file.Files

class MonorepoStepIOSpec extends Specification with CatsEffect {

  "MonorepoStepIO.compose" should {

    "run global checks before actions" in {
      contextResource.use { ctx =>
        Ref.of[IO, List[String]](Nil).flatMap { log =>
          val step = MonorepoStepIO.Global(
            name = "test-step",
            check = c => log.update(_ :+ "check").as(c),
            action = c => log.update(_ :+ "action").as(c)
          )

          MonorepoStepIO.compose(Seq(step))(ctx) *>
            log.get.map(_ must_== List("check", "action"))
        }
      }
    }

    "abort on check failure without running actions" in {
      contextResource.use { ctx =>
        Ref.of[IO, List[String]](Nil).flatMap { log =>
          val step = MonorepoStepIO.Global(
            name = "failing-check",
            check = _ => IO.raiseError(new RuntimeException("check failed")),
            action = c => log.update(_ :+ "action").as(c)
          )

          MonorepoStepIO.compose(Seq(step))(ctx).attempt.flatMap { result =>
            log.get.map { obs =>
              (result must beLeft.like { case e: RuntimeException =>
                e.getMessage must contain("check failed")
              }) and (obs must_== List())
            }
          }
        }
      }
    }

    "iterate PerProject steps over all projects" in {
      contextResource.use { ctx =>
        Ref.of[IO, List[String]](Nil).flatMap { log =>
          val projects = Seq(dummyProject("core"), dummyProject("api"))
          val pCtx     = ctx.withProjects(projects)

          val step = MonorepoStepIO.PerProject(
            name = "per-project-step",
            action = (c, proj) => log.update(_ :+ proj.name).as(c)
          )

          MonorepoStepIO.compose(Seq(step))(pCtx) *>
            log.get.map(_ must_== List("core", "api"))
        }
      }
    }

    "abort release when a per-project step fails" in {
      contextResource.use { ctx =>
        Ref.of[IO, List[String]](Nil).flatMap { log =>
          val projects = Seq(dummyProject("core"), dummyProject("api"))
          val pCtx     = ctx.withProjects(projects)

          val failingStep = MonorepoStepIO.PerProject(
            name = "failing-step",
            action = (c, proj) =>
              if (proj.name == "core") IO.raiseError(new RuntimeException("core failed"))
              else log.update(_ :+ s"step1:${proj.name}").as(c)
          )

          val secondStep = MonorepoStepIO.PerProject(
            name = "second-step",
            action = (c, proj) => log.update(_ :+ s"step2:${proj.name}").as(c)
          )

          MonorepoStepIO.compose(Seq(failingStep, secondStep))(pCtx).attempt.flatMap { result =>
            log.get.map { obs =>
              // step1 ran for api (core failed), step2 skipped entirely because ctx.failed is true
              (result must beLeft.like { case e: RuntimeException =>
                e.getMessage must contain("Monorepo release process failed")
              }) and (obs must_== List("step1:api"))
            }
          }
        }
      }
    }

    "thread MonorepoContext through sequential steps" in {
      contextResource.use { ctx =>
        val step1 = MonorepoStepIO.Global(
          name = "set-attr",
          action = c => IO.pure(c.withAttr("key", "value"))
        )
        val step2 = MonorepoStepIO.Global(
          name = "read-attr",
          action = c =>
            if (c.attr("key").contains("value")) IO.pure(c.withAttr("verified", "true"))
            else IO.raiseError(new RuntimeException("attribute not threaded"))
        )

        MonorepoStepIO.compose(Seq(step1, step2))(ctx).map { result =>
          result.attr("verified") must beSome("true")
        }
      }
    }

    "mark entire release as failed when global action fails" in {
      contextResource.use { ctx =>
        val step = MonorepoStepIO.Global(
          name = "global-fail",
          action = _ => IO.raiseError(new RuntimeException("global failure"))
        )

        MonorepoStepIO.compose(Seq(step))(ctx).attempt.map {
          case Left(e: RuntimeException) =>
            e.getMessage must contain("Monorepo release process failed")
          case other                     => ko(s"Expected RuntimeException but got $other")
        }
      }
    }

    "run PerProject checks for all projects during check phase" in {
      contextResource.use { ctx =>
        Ref.of[IO, List[String]](Nil).flatMap { log =>
          val projects = Seq(dummyProject("core"), dummyProject("api"))
          val pCtx     = ctx.withProjects(projects)

          val step = MonorepoStepIO.PerProject(
            name = "checked-step",
            check = (c, proj) => log.update(_ :+ s"check:${proj.name}").as(c),
            action = (c, proj) => log.update(_ :+ s"action:${proj.name}").as(c)
          )

          MonorepoStepIO.compose(Seq(step))(pCtx) *>
            log.get.map(_ must_== List("check:core", "check:api", "action:core", "action:api"))
        }
      }
    }

    "detect FailureCommand during global check phase" in {
      contextResource.use { ctx =>
        Ref.of[IO, List[String]](Nil).flatMap { log =>
          val step = MonorepoStepIO.Global(
            name = "failing-check",
            check = c =>
              IO.pure(
                c.copy(state =
                  c.state
                    .copy(remainingCommands = Compat.FailureCommand +: c.state.remainingCommands)
                )
              ),
            action = c => log.update(_ :+ "action").as(c)
          )

          MonorepoStepIO.compose(Seq(step))(ctx).attempt.flatMap { result =>
            log.get.map { obs =>
              (result must beLeft.like { case e: RuntimeException =>
                e.getMessage must contain("Check phase failed")
              }) and (obs must_== List())
            }
          }
        }
      }
    }

    "detect FailureCommand during per-project check phase" in {
      contextResource.use { ctx =>
        Ref.of[IO, List[String]](Nil).flatMap { log =>
          val projects = Seq(dummyProject("core"), dummyProject("api"))
          val pCtx     = ctx.withProjects(projects)

          val step = MonorepoStepIO.PerProject(
            name = "failing-per-project-check",
            check = (c, proj) =>
              if (proj.name == "core")
                IO.pure(
                  c.copy(state =
                    c.state
                      .copy(remainingCommands = Compat.FailureCommand +: c.state.remainingCommands)
                  )
                )
              else log.update(_ :+ s"check:${proj.name}").as(c),
            action = (c, proj) => log.update(_ :+ s"action:${proj.name}").as(c)
          )

          MonorepoStepIO.compose(Seq(step))(pCtx).attempt.flatMap { result =>
            log.get.map { obs =>
              // api's check still runs within the same step; failure detected after step completes
              // but no actions should run
              (result must beLeft.like { case e: RuntimeException =>
                e.getMessage must contain("Check phase failed")
              }) and (obs must_== List("check:api"))
            }
          }
        }
      }
    }
  }

  private def dummyProject(name: String): ProjectReleaseInfo =
    ProjectReleaseInfo(
      ref = ProjectRef(new java.net.URI("file:///tmp/test"), name),
      name = name,
      baseDir = new java.io.File(s"/tmp/test/$name"),
      versionFile = new java.io.File(s"/tmp/test/$name/version.sbt")
    )

  private val contextResource: Resource[IO, MonorepoContext] =
    Resource
      .make(IO(Files.createTempDirectory("monorepo-step-spec").toFile))(dir =>
        IO(TestHelpers.deleteRecursively(dir))
      )
      .map { dir =>
        val logFile       = new File(dir, "sbt-test.log")
        val globalLogging =
          GlobalLogging.initial(
            MainAppender.globalDefault(ConsoleOut.systemOut),
            logFile,
            ConsoleOut.systemOut
          )

        val state = State(
          configuration = dummyAppConfiguration(dir),
          definedCommands = Nil,
          exitHooks = Set.empty,
          onFailure = None,
          remainingCommands = Nil,
          history = State.newHistory,
          attributes = AttributeMap.empty,
          globalLogging = globalLogging,
          currentCommand = None,
          next = State.Continue
        )

        MonorepoContext(state = state)
      }

  private class DummyAppMain extends AppMain {
    override def run(configuration: AppConfiguration): MainResult = new MainResult {}
  }

  private def dummyAppConfiguration(baseDir: File): AppConfiguration = {
    val launcher: Launcher = new Launcher {
      override def getScala(version: String): ScalaProvider                                   = ???
      override def getScala(version: String, reason: String): ScalaProvider                   = ???
      override def getScala(version: String, reason: String, scalaOrg: String): ScalaProvider = ???
      override def app(id: ApplicationID, version: String): AppProvider                       = ???
      override def topLoader(): ClassLoader                                                   = getClass.getClassLoader
      override def globalLock(): GlobalLock                                                   = new GlobalLock {
        override def apply[T](lockFile: File, run: java.util.concurrent.Callable[T]): T = run.call()
      }
      override def bootDirectory(): File                                                      = baseDir
      override def ivyRepositories(): Array[Repository]                                       = Array.empty
      override def appRepositories(): Array[Repository]                                       = Array.empty
      override def isOverrideRepositories(): Boolean                                          = false
      override def ivyHome(): File                                                            = baseDir
      override def checksums(): Array[String]                                                 = Array.empty
    }

    val appId: ApplicationID = new ApplicationID {
      override def groupID(): String                 = "test"
      override def name(): String                    = "test"
      override def version(): String                 = "0.0.0"
      override def mainClass(): String               = "test.Main"
      override def mainComponents(): Array[String]   = Array.empty
      override def crossVersioned(): Boolean         = false
      override def crossVersionedValue(): CrossValue = CrossValue.Disabled
      override def classpathExtra(): Array[File]     = Array.empty
    }

    lazy val scalaProvider: ScalaProvider = new ScalaProvider {
      override def launcher(): Launcher                = launcher
      override def version(): String                   = "2.12.21"
      override def loader(): ClassLoader               = getClass.getClassLoader
      override def jars(): Array[File]                 = Array.empty
      override def libraryJar(): File                  = new File(baseDir, "scala-library.jar")
      override def compilerJar(): File                 = new File(baseDir, "scala-compiler.jar")
      override def app(id: ApplicationID): AppProvider = appProvider
    }

    lazy val componentProvider: ComponentProvider = new ComponentProvider {
      override def componentLocation(id: String): File                     = new File(baseDir, s"component-$id")
      override def component(id: String): Array[File]                      = Array.empty
      override def defineComponent(id: String, files: Array[File]): Unit   = ()
      override def addToComponent(id: String, files: Array[File]): Boolean = true
      override def lockFile(): File                                        = new File(baseDir, ".components.lock")
    }

    lazy val appProvider: AppProvider = new AppProvider {
      override def scalaProvider(): ScalaProvider   = scalaProvider
      override def id(): ApplicationID              = appId
      override def loader(): ClassLoader            = getClass.getClassLoader
      override def mainClass(): Class[_ <: AppMain] = classOf[DummyAppMain]
      override def entryPoint(): Class[_]           = classOf[DummyAppMain]
      override def newMain(): AppMain               = new DummyAppMain
      override def mainClasspath(): Array[File]     = Array.empty
      override def components(): ComponentProvider  = componentProvider
    }

    new AppConfiguration {
      override def arguments(): Array[String] = Array.empty
      override def baseDirectory(): File      = baseDir
      override def provider(): AppProvider    = appProvider
    }
  }
}
