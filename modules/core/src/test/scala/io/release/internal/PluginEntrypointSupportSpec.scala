package io.release.runtime.command

import cats.effect.IO
import io.release.TestRepoFiles
import munit.CatsEffectSuite
import sbt.*
import sbt.complete.DefaultParsers.success

class PluginEntrypointSupportSpec extends CatsEffectSuite {

  test("pluginSettings appends command registration after defaults") {
    IO {
      val settings = PluginEntrypointSupport.pluginSettings(
        defaultSettings = Seq(
          PluginEntrypointSupportSpec.DefaultAKey := "default-a",
          PluginEntrypointSupportSpec.DefaultBKey := "default-b"
        ),
        commandSetting = PluginEntrypointSupport.commandSetting("releaseIO")(
          _ => success(Seq.empty[String]),
          (state, _) => state
        )
      )
      val labels   = settings.map(_.key.key.label)

      assertEquals(
        labels,
        Seq(
          PluginEntrypointSupportSpec.DefaultAKey.key.label,
          PluginEntrypointSupportSpec.DefaultBKey.key.label,
          "commands"
        )
      )
      assertEquals(labels.count(_ == "commands"), 1)
    }
  }

  test("source cleanup - shared plugin shell only keeps setting helpers") {
    IO {
      val shellSource              =
        TestRepoFiles.readString(
          "modules/runtime/src/main/scala/io/release/runtime/command/PluginEntrypointSupport.scala"
        )
      val commandModeParsingSource =
        TestRepoFiles.readString(
          "modules/runtime/src/main/scala/io/release/runtime/command/CommandModeParsing.scala"
        )
      val releaseCliSource         =
        TestRepoFiles.readString(
          "modules/core/src/main/scala/io/release/core/internal/ReleaseCli.scala"
        )
      val monorepoCliSource        =
        TestRepoFiles.readString(
          "modules/monorepo/src/main/scala/io/release/monorepo/internal/MonorepoCli.scala"
        )

      assert(!shellSource.contains("DispatchAdapter"))
      assert(!shellSource.contains("ParsedCommand"))
      assert(!shellSource.contains("handleTokens("))
      assert(!shellSource.contains("sealed trait CommandMode"))
      assert(commandModeParsingSource.contains("sealed trait CommandMode"))
      assert(releaseCliSource.contains("CommandModeParsing.CommandMode"))
      assert(monorepoCliSource.contains("CommandModeParsing.CommandMode"))
    }
  }
}

object PluginEntrypointSupportSpec {

  val DefaultAKey: SettingKey[String] =
    SettingKey[String](
      "pluginEntrypointSupportSpecDefaultA",
      "Plugin entrypoint support spec default A"
    )

  val DefaultBKey: SettingKey[String] =
    SettingKey[String](
      "pluginEntrypointSupportSpecDefaultB",
      "Plugin entrypoint support spec default B"
    )
}
