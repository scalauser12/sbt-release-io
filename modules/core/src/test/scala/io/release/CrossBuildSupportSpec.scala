package io.release

import org.specs2.mutable.Specification
import sbt.*
import sbt.Keys.*

class CrossBuildSupportSpec extends Specification {

  "CrossBuildSupport.crossExclude" should {

    "exclude GlobalScope / scalaVersion" in {
      val setting = (GlobalScope / Keys.scalaVersion := "2.12.21")
      CrossBuildSupport.crossExclude(setting) must beTrue
    }

    "exclude GlobalScope / scalaHome" in {
      val setting = (GlobalScope / Keys.scalaHome := None)
      CrossBuildSupport.crossExclude(setting) must beTrue
    }

    "keep other global settings" in {
      val setting = (GlobalScope / Keys.scalacOptions := Seq("-deprecation"))
      CrossBuildSupport.crossExclude(setting) must beFalse
    }

    "keep config-scoped scalaVersion" in {
      val setting = (Compile / Keys.scalaVersion := "2.12.21")
      CrossBuildSupport.crossExclude(setting) must beFalse
    }
  }
}
