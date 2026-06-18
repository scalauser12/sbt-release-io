package io.release.runtime.command

import cats.effect.IO
import io.release.TestRepoFiles
import munit.CatsEffectSuite

class PluginEntrypointSpec extends CatsEffectSuite {

  test("source cleanup - shared plugin shell only keeps setting helpers") {
    IO {
      val shellSource             =
        TestRepoFiles.readString(
          "modules/runtime/src/main/scala/io/release/runtime/command/PluginEntrypoint.scala"
        )
      val releaseCommandCliSource =
        TestRepoFiles.readString(
          "modules/runtime/src/main/scala/io/release/runtime/command/ReleaseCommandCli.scala"
        )
      val releaseCliSource        =
        TestRepoFiles.readString(
          "modules/core/src/main/scala/io/release/core/internal/ReleaseCommandParsing.scala"
        )
      val monorepoCliSource       =
        TestRepoFiles.readString(
          "modules/monorepo/src/main/scala/io/release/monorepo/internal/MonorepoCommandParsing.scala"
        )

      assert(!shellSource.contains("DispatchAdapter"))
      assert(!shellSource.contains("ParsedCommand"))
      assert(!shellSource.contains("handleTokens("))
      assert(!shellSource.contains("sealed trait CommandMode"))
      assert(releaseCommandCliSource.contains("sealed trait CommandMode"))
      assert(releaseCliSource.contains("ReleaseCommandCli.CommandMode"))
      assert(monorepoCliSource.contains("ReleaseCommandCli.CommandMode"))
    }
  }
}
