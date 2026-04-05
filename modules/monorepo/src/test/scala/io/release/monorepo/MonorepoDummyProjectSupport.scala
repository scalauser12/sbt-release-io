package io.release.monorepo

import cats.effect.IO
import munit.AnyFixture
import munit.CatsEffectSuite

trait MonorepoDummyProjectSupport extends CatsEffectSuite {

  protected def dummyProjectFixturePrefix: String =
    "sbt-release-io-monorepo-dummy-build"

  private val dummyProjectFactoryFixture = ResourceSuiteLocalFixture(
    "monorepo-dummy-project-factory",
    MonorepoTestSupport.dummyProjectFactoryResource(dummyProjectFixturePrefix)
  )

  override def munitFixtures: Seq[AnyFixture[?]] =
    super.munitFixtures ++ Seq(dummyProjectFactoryFixture)

  protected def dummyProject(name: String): IO[ProjectReleaseInfo] =
    dummyProjectFactoryFixture().dummyProject(name)

  protected def dummyProjects(names: String*): IO[Seq[ProjectReleaseInfo]] =
    names.foldLeft(IO.pure(Vector.empty[ProjectReleaseInfo])) { (acc, name) =>
      for {
        projects <- acc
        project  <- dummyProject(name)
      } yield projects :+ project
    }
}
