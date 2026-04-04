package io.release.internal

import cats.effect.IO
import io.release.TestRepoFiles
import io.release.TestSupport
import munit.CatsEffectSuite
import sbt.*
import sbt.complete.DefaultParsers.success

class PluginEntrypointSupportSpec extends CatsEffectSuite {
  import PluginEntrypointSupport.CommandMode

  test("handleTokens logs prefixed parse failures and returns a failed state") {
    TestSupport.tempDirResource("plugin-entrypoint-invalid").use { dir =>
      IO {
        val buffered = TestSupport.bufferedState(dir)
        val result   = PluginEntrypointSupport.handleTokens[String](
          state = buffered.state,
          tokens = Seq("wut"),
          logPrefix = "[plugin-shell]",
          commandName = "releaseIO",
          dispatch = PluginEntrypointSupport.DispatchAdapter(
            parse = (_, _) => Left("invalid command"),
            help = identity,
            check = (state, _) => state,
            run = (state, _) => state
          )
        )
        val log      = buffered.consoleBuffer.toString("UTF-8")
        val failed   = buffered.state.fail

        assertEquals(result.next.getClass.getName, failed.next.getClass.getName)
        assertEquals(result.remainingCommands, failed.remainingCommands)
        assert(log.contains("[plugin-shell] invalid command"))
      }
    }
  }

  test("handleTokens dispatches help, check, and run exactly once") {
    TestSupport.tempDirResource("plugin-entrypoint-dispatch").use { dir =>
      IO {
        val state         = TestSupport.dummyState(dir)
        var helpCalls     = 0
        var checkCalls    = 0
        var runCalls      = 0
        var observedArgs  = Vector.empty[Seq[String]]
        val helpDispatch  = PluginEntrypointSupport.DispatchAdapter[String](
          parse = (_, _) =>
            Right(PluginEntrypointSupport.ParsedCommand(CommandMode.Help, Seq("ignored"))),
          help = current => {
            helpCalls += 1
            current
          },
          check = (current, args) => {
            checkCalls += 1
            observedArgs :+= args
            current
          },
          run = (current, args) => {
            runCalls += 1
            observedArgs :+= args
            current
          }
        )
        val checkDispatch = helpDispatch.copy(
          parse =
            (_, _) => Right(PluginEntrypointSupport.ParsedCommand(CommandMode.Check, Seq("check")))
        )
        val runDispatch   = helpDispatch.copy(
          parse =
            (_, _) => Right(PluginEntrypointSupport.ParsedCommand(CommandMode.Run, Seq("run")))
        )

        PluginEntrypointSupport.handleTokens(
          state,
          Seq("help"),
          "[plugin-shell]",
          "releaseIO",
          helpDispatch
        )
        PluginEntrypointSupport.handleTokens(
          state,
          Seq("check"),
          "[plugin-shell]",
          "releaseIO",
          checkDispatch
        )
        PluginEntrypointSupport.handleTokens(
          state,
          Seq("run"),
          "[plugin-shell]",
          "releaseIO",
          runDispatch
        )

        assertEquals(helpCalls, 1)
        assertEquals(checkCalls, 1)
        assertEquals(runCalls, 1)
        assertEquals(observedArgs, Vector(Seq("check"), Seq("run")))
      }
    }
  }

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

  test("source cleanup - only the shared plugin shell declares CommandMode") {
    IO {
      val commandModeOccurrences = Seq(
        "modules/core/src/main/scala/io/release/internal/PluginEntrypointSupport.scala",
        "modules/core/src/main/scala/io/release/internal/ReleaseCli.scala",
        "modules/monorepo/src/main/scala/io/release/monorepo/MonorepoCli.scala"
      ).map(TestRepoFiles.readString).map(_.split("sealed trait CommandMode", -1).length - 1).sum

      assertEquals(commandModeOccurrences, 1)
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
