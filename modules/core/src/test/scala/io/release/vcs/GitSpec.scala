package io.release.vcs

import cats.effect.IO
import cats.effect.Ref
import io.release.TestAssertions.assertFailure
import io.release.TestSupport
import munit.CatsEffectSuite

import java.io.ByteArrayInputStream
import java.io.ByteArrayOutputStream
import java.io.File
import java.io.InputStream
import java.io.OutputStream
import java.nio.file.Files
import java.lang.ProcessBuilder.Redirect
import java.nio.charset.StandardCharsets
import java.time.Duration as JavaDuration
import java.time.Instant
import java.util.Optional
import java.util.concurrent.CompletableFuture
import scala.concurrent.duration.*
import scala.jdk.CollectionConverters.*

class GitSpec extends CatsEffectSuite {
  private val fixturePrefix = "git-spec"

  test("executableNameFor - return git.exe for Windows-like OS names") {
    IO(assertEquals(GitProcessSupport.executableNameFor("Windows 11"), "git.exe"))
  }

  test("executableNameFor - return git for non-Windows OS names") {
    IO(assertEquals(GitProcessSupport.executableNameFor("Linux"), "git"))
  }

  test("runLines - preserve stderr on git failure in a non-repository directory") {
    TestSupport.tempDirResource(s"$fixturePrefix-stderr").use { dir =>
      for {
        result <- GitProcessSupport.runCommandResult(dir, Seq("status", "--porcelain"))
        _       = assert(result.exitCode != 0)
        _       = assert(result.stderr.nonEmpty)
        _      <-
          GitProcessSupport.runLines(dir, Seq("status", "--porcelain"))("git status").attempt.map {
            case Left(err: IllegalStateException) =>
              assert(
                err.getMessage.contains(
                  s"git status failed with exit code ${result.exitCode}"
                )
              )
              assert(err.getMessage.contains(result.stderr))
            case Left(other)                      =>
              fail(
                s"Expected IllegalStateException, got ${other.getClass.getName}: ${other.getMessage}"
              )
            case Right(output)                    =>
              fail(s"Expected git status failure, got output: ${output.mkString(", ")}")
          }
      } yield ()
    }
  }

  test("attachedJavaCmd - inherit terminal stdio for runCmd operations") {
    TestSupport.tempDirResource(s"$fixturePrefix-attached-stdio").use { dir =>
      IO {
        val builder = GitProcessSupport.attachedJavaCmd(dir, "status")

        assertEquals(builder.redirectInput(), Redirect.INHERIT)
        assertEquals(builder.redirectOutput(), Redirect.INHERIT)
        assertEquals(builder.redirectError(), Redirect.INHERIT)
      }
    }
  }

  test("captureLines - decode git output with the provided charset") {
    val expected = "cafe\u00e9"
    val bytes    = s"$expected\n".getBytes(StandardCharsets.ISO_8859_1)

    GitProcessSupport
      .captureLines(new ByteArrayInputStream(bytes), StandardCharsets.ISO_8859_1)
      .map(lines => assertEquals(lines, Vector(expected)))
  }

  test("cleanupManagedProcess - destroy descendants when the root process already exited") {
    val descendant = new FakeProcessHandle(2L, initialAlive = true)
    val process    = new FakeProcess(1L, initialAlive = false, descendants = Vector(descendant))
    val managed    = GitProcessSupport.ProcessTree.ManagedProcess.create(process)

    GitProcessSupport.ProcessTree.cleanupManagedProcess(managed, 1.second, completed = false).map {
      _ =>
        assertEquals(process.destroyCalls, 1)
        assertEquals(process.destroyForciblyCalls, 0)
        assertEquals(descendant.destroyCalls, 1)
        assertEquals(descendant.destroyForciblyCalls, 0)
        assert(process.inputClosed)
        assert(process.errorClosed)
        assert(process.outputClosed)
        assert(!descendant.isAlive)
    }
  }

  test("cleanupManagedProcess - destroy root and descendants when completion aborts") {
    val descendant = new FakeProcessHandle(2L, initialAlive = true)
    val process    = new FakeProcess(1L, initialAlive = true, descendants = Vector(descendant))
    val managed    = GitProcessSupport.ProcessTree.ManagedProcess.create(process)

    GitProcessSupport.ProcessTree.cleanupManagedProcess(managed, 1.second, completed = false).map {
      _ =>
        assertEquals(process.destroyCalls, 1)
        assertEquals(process.destroyForciblyCalls, 0)
        assertEquals(descendant.destroyCalls, 1)
        assertEquals(descendant.destroyForciblyCalls, 0)
        assert(process.inputClosed)
        assert(process.errorClosed)
        assert(process.outputClosed)
        assert(!descendant.isAlive)
    }
  }

  test("upstreamTrackingHash - return None when the configured upstream ref is missing") {
    TestSupport.gitRepoWithBareRemoteResource(s"$fixturePrefix-upstream-missing").use {
      case (repo, _) =>
        for {
          _      <- IO.blocking(
                      TestSupport.runGit(repo, "config", "branch.main.merge", "refs/heads/missing")
                    )
          result <- new Git(repo).upstreamTrackingHash
        } yield assertEquals(result, None)
    }
  }

  test(
    "upstreamTrackingHash - propagate InvalidUpstreamConfigException when branch.X.merge is malformed"
  ) {
    TestSupport.gitRepoWithBareRemoteResource(s"$fixturePrefix-upstream-tracking-malformed").use {
      case (repo, _) =>
        IO.blocking(
          TestSupport.runGit(repo, "config", "branch.main.merge", "refs/tags/v1.0.0")
        ) *>
          assertFailure[InvalidUpstreamConfigException, Option[String]](
            new Git(repo).upstreamTrackingHash
          ) { err =>
            assert(err.getMessage.contains("refs/tags/v1.0.0"))
            assert(err.getMessage.contains("must use the 'refs/heads/' format"))
          }
    }
  }

  test("isBehindRemote - return true even when a local tag shares the branch name") {
    TestSupport.gitRepoWithBareRemoteResource(s"$fixturePrefix-behind-tag-collision").use {
      case (repo, _) =>
        // Layout: local branch main = A, tag main = B, origin/main = B. With unqualified refs,
        // git's dwim resolution prefers refs/tags/main over refs/heads/main, so `main..origin/main`
        // resolves to `B..B` (empty) and silently reports NOT behind. The qualified form correctly
        // resolves to `A..B` (one commit) and reports behind.
        for {
          _      <- IO.blocking {
                      val baseHash = TestSupport.runGit(repo, "rev-parse", "HEAD").trim
                      TestSupport.runGit(repo, "commit", "--allow-empty", "-m", "advance main")
                      TestSupport.runGit(repo, "push", "origin", "main")
                      TestSupport.runGit(repo, "tag", "main")
                      TestSupport.runGit(repo, "reset", "--hard", baseHash)
                    }
          behind <- new Git(repo).isBehindRemote
        } yield assert(behind, "expected isBehindRemote to be true under tag collision")
    }
  }

  test("isBehindRemote - return false when local matches remote even under tag collision") {
    TestSupport.gitRepoWithBareRemoteResource(s"$fixturePrefix-behind-tag-collision-noop").use {
      case (repo, _) =>
        for {
          _      <- IO.blocking(TestSupport.runGit(repo, "tag", "main"))
          behind <- new Git(repo).isBehindRemote
        } yield assert(!behind, "expected isBehindRemote to be false when local matches remote")
    }
  }

  test("hasUpstream - return true when the branch tracks a remote") {
    TestSupport.gitRepoWithBareRemoteResource(s"$fixturePrefix-has-upstream").use {
      case (repo, _) =>
        new Git(repo).hasUpstream.map(result => assertEquals(result, true))
    }
  }

  test("hasUpstream - surface the real git error when .git/config is corrupted") {
    TestSupport.gitRepoWithCommitResource(s"$fixturePrefix-has-upstream-corrupt").use { repo =>
      IO.blocking {
        val configFile = new File(repo, ".git/config")
        Files.write(
          configFile.toPath,
          "\n[broken section\nremote = origin\n".getBytes(StandardCharsets.UTF_8)
        )
        ()
      } *>
        new Git(repo).hasUpstream.attempt.map {
          case Left(err: IllegalStateException) =>
            assert(err.getMessage.contains("exit code"))
            assert(
              err.getMessage.toLowerCase.contains("fatal") ||
                err.getMessage.toLowerCase.contains("bad")
            )
          case Left(other)                      =>
            fail(
              s"Expected IllegalStateException, got ${other.getClass.getName}: ${other.getMessage}"
            )
          case Right(value)                     =>
            fail(s"Expected failure, got value: $value")
        }
    }
  }

  test("validateTagName - accept a well-formed tag name") {
    TestSupport.gitRepoWithCommitResource(s"$fixturePrefix-validate-tag-ok").use { repo =>
      new Git(repo).validateTagName("v1.2.3").void
    }
  }

  test("validateTagName - reject an empty tag name without invoking git") {
    TestSupport.gitRepoWithCommitResource(s"$fixturePrefix-validate-tag-empty").use { repo =>
      assertFailure[InvalidTagNameException, Unit](
        new Git(repo).validateTagName("").void
      ) { err =>
        assert(err.getMessage.contains("must be non-empty"))
        assert(err.getMessage.contains("releaseIOVcsTagName"))
      }
    }
  }

  test("validateTagName - reject names with spaces") {
    TestSupport.gitRepoWithCommitResource(s"$fixturePrefix-validate-tag-space").use { repo =>
      assertFailure[InvalidTagNameException, Unit](
        new Git(repo).validateTagName("bad tag").void
      ) { err =>
        assert(err.getMessage.contains("Invalid tag name 'bad tag'"))
        assert(err.getMessage.contains("git ref rules"))
      }
    }
  }

  test("validateTagName - reject names starting with '-'") {
    // `git check-ref-format refs/tags/-m` returns 0, so without an explicit
    // leading-dash check we would accept names that `git tag` later parses
    // as CLI options (creating the wrong tag or none at all, after the
    // release commit has already landed).
    TestSupport.gitRepoWithCommitResource(s"$fixturePrefix-validate-tag-leading-dash").use { repo =>
      assertFailure[InvalidTagNameException, Unit](
        new Git(repo).validateTagName("-m").void
      ) { err =>
        assert(err.getMessage.contains("Invalid tag name '-m'"))
        assert(err.getMessage.contains("must not start with '-'"))
      }
    }
  }

  test("validateTagName - reject names containing '..'") {
    TestSupport.gitRepoWithCommitResource(s"$fixturePrefix-validate-tag-dots").use { repo =>
      assertFailure[InvalidTagNameException, Unit](
        new Git(repo).validateTagName("v1..0").void
      )(err => assert(err.getMessage.contains("Invalid tag name 'v1..0'")))
    }
  }

  test("validateTagName - reject names ending with '.lock'") {
    TestSupport.gitRepoWithCommitResource(s"$fixturePrefix-validate-tag-lock").use { repo =>
      assertFailure[InvalidTagNameException, Unit](
        new Git(repo).validateTagName("release.lock").void
      )(err => assert(err.getMessage.contains("Invalid tag name 'release.lock'")))
    }
  }

  test("existsTag - surface the real git error when .git/config is corrupted") {
    TestSupport.gitRepoWithCommitResource(s"$fixturePrefix-exists-tag-corrupt").use { repo =>
      IO.blocking {
        val configFile = new File(repo, ".git/config")
        Files.write(
          configFile.toPath,
          "\n[broken section\nremote = origin\n".getBytes(StandardCharsets.UTF_8)
        )
        ()
      } *>
        new Git(repo).existsTag("v1.0.0").attempt.map {
          case Left(err: IllegalStateException) =>
            assert(err.getMessage.contains("exit code"))
            assert(
              err.getMessage.toLowerCase.contains("fatal") ||
                err.getMessage.toLowerCase.contains("bad")
            )
          case Left(other)                      =>
            fail(
              s"Expected IllegalStateException, got ${other.getClass.getName}: ${other.getMessage}"
            )
          case Right(value)                     =>
            fail(s"Expected failure, got value: $value")
        }
    }
  }

  test("currentBranch - return the branch name even when a local tag shares it") {
    TestSupport.gitRepoWithCommitResource(s"$fixturePrefix-current-branch-tag-collision").use {
      repo =>
        IO.blocking {
          TestSupport.runGit(repo, "branch", "-M", "main")
          TestSupport.runGit(repo, "tag", "main")
        } *>
          new Git(repo).currentBranch.map(branch => assertEquals(branch, "main"))
    }
  }

  test("currentBranch - raise a detached-HEAD error when HEAD is not on a branch") {
    TestSupport.gitRepoWithCommitResource(s"$fixturePrefix-current-branch-detached").use { repo =>
      IO.blocking {
        val head = TestSupport.runGit(repo, "rev-parse", "HEAD").trim
        TestSupport.runGit(repo, "checkout", "--detach", head)
        ()
      } *>
        assertFailure[IllegalStateException, String](new Git(repo).currentBranch) { err =>
          assert(err.getMessage.contains("HEAD is detached"))
        }
    }
  }

  test("trackingRemote - raise a structured error when branch.X.remote is unset") {
    TestSupport.gitRepoWithCommitResource(s"$fixturePrefix-tracking-remote-missing").use { repo =>
      IO.blocking(TestSupport.runGit(repo, "branch", "-M", "main")) *>
        assertFailure[IllegalStateException, String](new Git(repo).trackingRemote) { err =>
          assert(err.getMessage.contains("no configured tracking remote"))
          assert(err.getMessage.contains("branch.main.remote"))
        }
    }
  }

  test("trackingRemote - reject a blank remote value with a structured error") {
    TestSupport.gitRepoWithCommitResource(s"$fixturePrefix-tracking-remote-blank").use { repo =>
      IO.blocking {
        TestSupport.runGit(repo, "branch", "-M", "main")
        TestSupport.runGit(repo, "config", "branch.main.remote", "   ")
      } *>
        assertFailure[IllegalStateException, String](new Git(repo).trackingRemote) { err =>
          assert(err.getMessage.contains("Tracking remote for branch 'main' is empty"))
          assert(err.getMessage.contains("branch.main.remote"))
        }
    }
  }

  test("trackingRemote - surface the real git error when .git/config is corrupted") {
    TestSupport.gitRepoWithCommitResource(s"$fixturePrefix-tracking-remote-corrupt").use { repo =>
      IO.blocking {
        val configFile = new File(repo, ".git/config")
        Files.write(
          configFile.toPath,
          "\n[broken section\nremote = origin\n".getBytes(StandardCharsets.UTF_8)
        )
        ()
      } *>
        new Git(repo).trackingRemote.attempt.map {
          case Left(err: InvalidUpstreamConfigException) =>
            fail(
              s"Expected a real git error, got InvalidUpstreamConfigException: ${err.getMessage}"
            )
          case Left(err: IllegalStateException)          =>
            assert(err.getMessage.contains("exit code"))
            assert(
              err.getMessage.toLowerCase.contains("fatal") ||
                err.getMessage.toLowerCase.contains("bad")
            )
          case Left(other)                               =>
            fail(
              s"Expected IllegalStateException, got ${other.getClass.getName}: ${other.getMessage}"
            )
          case Right(value)                              =>
            fail(s"Expected failure, got value: $value")
        }
    }
  }

  test("trackingRemote - reject a literal-empty remote value with a structured error") {
    TestSupport.gitRepoWithCommitResource(s"$fixturePrefix-tracking-remote-empty").use { repo =>
      IO.blocking {
        TestSupport.runGit(repo, "branch", "-M", "main")
        TestSupport.runGit(repo, "config", "branch.main.remote", "")
      } *>
        assertFailure[InvalidUpstreamConfigException, String](
          new Git(repo).trackingRemote
        ) { err =>
          assert(err.getMessage.contains("Tracking remote for branch 'main' is empty"))
          assert(err.getMessage.contains("branch.main.remote"))
        }
    }
  }

  test("trackingRemote - reject '.' remotes with a structured error") {
    TestSupport.gitRepoWithCommitResource(s"$fixturePrefix-tracking-remote-dot").use { repo =>
      IO.blocking {
        TestSupport.runGit(repo, "branch", "-M", "main")
        TestSupport.runGit(repo, "config", "branch.main.remote", ".")
      } *>
        assertFailure[IllegalStateException, String](new Git(repo).trackingRemote) { err =>
          assert(err.getMessage.contains("tracks a local branch"))
          assert(err.getMessage.contains("branch.main.remote = '.'"))
        }
    }
  }

  test("trackingRemote - trim surrounding whitespace from the configured remote") {
    TestSupport.gitRepoWithCommitResource(s"$fixturePrefix-tracking-remote-padded").use { repo =>
      IO.blocking {
        TestSupport.runGit(repo, "branch", "-M", "main")
        TestSupport.runGit(repo, "config", "branch.main.remote", "  origin  ")
      } *>
        new Git(repo).trackingRemote.map(result => assertEquals(result, "origin"))
    }
  }

  test(
    "isBehindRemote - propagate InvalidUpstreamConfigException when branch.X.merge is not a refs/heads/ ref"
  ) {
    TestSupport.gitRepoWithBareRemoteResource(s"$fixturePrefix-upstream-non-heads").use {
      case (repo, _) =>
        IO.blocking(
          TestSupport.runGit(repo, "config", "branch.main.merge", "refs/tags/v1.0.0")
        ) *>
          assertFailure[InvalidUpstreamConfigException, Boolean](
            new Git(repo).isBehindRemote
          ) { err =>
            assert(err.getMessage.contains("refs/tags/v1.0.0"))
            assert(err.getMessage.contains("must use the 'refs/heads/' format"))
          }
    }
  }

  test("tagCommitHash - return None when the tag does not exist") {
    TestSupport.gitRepoWithCommitResource(s"$fixturePrefix-missing-tag").use { repo =>
      new Git(repo).tagCommitHash("v1.0.0").map(result => assertEquals(result, None))
    }
  }

  test("existsTag - return true when the tag exists") {
    TestSupport.gitRepoWithCommitResource(s"$fixturePrefix-existing-tag").use { repo =>
      for {
        _      <- IO.blocking(TestSupport.runGit(repo, "tag", "v1.0.0"))
        exists <- new Git(repo).existsTag("v1.0.0")
      } yield assertEquals(exists, true)
    }
  }

  test("runCmd - destroy the spawned git process when the fiber is canceled") {
    assume(new File("/bin/sh").exists(), "requires /bin/sh")

    TestSupport.gitRepoResource(s"$fixturePrefix-run-cmd-cancel").use { repo =>
      val pidFile = new File(repo, "run-cmd.pid")

      for {
        _     <- configureAlias(
                   repo,
                   "codexsleepcmd",
                   """echo $$ > "$0"; exec sleep 5""",
                   pidFile
                 )
        fiber <- GitProcessSupport
                   .runCmd(repo, Seq("codexsleepcmd"))("git codexsleepcmd")
                   .start
        _     <- waitForFile(pidFile, 5.seconds)
        _     <- fiber.cancel.timeout(1.second)
        pid   <- readPid(pidFile)
        alive <- waitForProcessToExit(pid, 2.seconds)
      } yield assertEquals(alive, false)
    }
  }

  test("runLines - destroy the spawned git process when the fiber is canceled") {
    assume(new File("/bin/sh").exists(), "requires /bin/sh")

    TestSupport.gitRepoResource(s"$fixturePrefix-run-lines-cancel").use { repo =>
      val pidFile = new File(repo, "run-lines.pid")

      for {
        _     <- configureAlias(
                   repo,
                   "codexsleeplines",
                   """echo $$ > "$0"; printf "%s\n" hello; printf "%s\n" boom 1>&2; exec sleep 5""",
                   pidFile
                 )
        fiber <- GitProcessSupport
                   .runLines(repo, Seq("codexsleeplines"))("git codexsleeplines")
                   .start
        _     <- waitForFile(pidFile, 5.seconds)
        _     <- fiber.cancel.timeout(1.second)
        pid   <- readPid(pidFile)
        alive <- waitForProcessToExit(pid, 2.seconds)
      } yield assertEquals(alive, false)
    }
  }

  test("runCommandWithTimeout - return the exit code when the process finishes in time") {
    assume(new File("/bin/sh").exists(), "requires /bin/sh")

    TestSupport.tempDirResource(fixturePrefix).use { dir =>
      GitProcessSupport
        .runCommandWithTimeout(
          new java.lang.ProcessBuilder("/bin/sh", "-c", "exit 0")
            .directory(dir)
            .redirectOutput(Redirect.DISCARD)
            .redirectError(Redirect.DISCARD),
          1.second
        )
        .map(result => assertEquals(result, Some(0)))
    }
  }

  test(
    "runCommandWithTimeout - return the exit code for a child-free command " +
      "that exits inside the recent root-exit grace window"
  ) {
    assume(new File("/bin/sh").exists(), "requires /bin/sh")

    TestSupport.tempDirResource(s"$fixturePrefix-grace-window-success").use { dir =>
      GitProcessSupport
        .runCommandWithTimeout(
          new java.lang.ProcessBuilder("/bin/sh", "-c", "exit 0")
            .directory(dir)
            .redirectOutput(Redirect.DISCARD)
            .redirectError(Redirect.DISCARD),
          150.millis
        )
        .map(result => assertEquals(result, Some(0)))
    }
  }

  test("runCommandWithTimeout - destroy the spawned process when the timeout elapses") {
    assume(new File("/bin/sh").exists(), "requires /bin/sh")

    TestSupport.tempDirResource(s"$fixturePrefix-timeout").use { dir =>
      val pidFile = new File(dir, "timeout.pid")

      for {
        result <- GitProcessSupport.runCommandWithTimeout(
                    new java.lang.ProcessBuilder(
                      "/bin/sh",
                      "-c",
                      """echo $$ > "$0"; exec sleep 5""",
                      pidFile.getAbsolutePath
                    )
                      .directory(dir)
                      .redirectOutput(Redirect.DISCARD)
                      .redirectError(Redirect.DISCARD),
                    250.millis
                  )
        _      <- waitForFile(pidFile, 5.seconds)
        pid    <- readPid(pidFile)
        alive  <- waitForProcessToExit(pid, 1.second)
      } yield {
        assertEquals(result, None)
        assert(pid > 0L)
        assertEquals(alive, false)
      }
    }
  }

  test(
    "runCommandWithTimeout - report the root exit code and terminate descendants that linger past the deadline"
  ) {
    assume(new File("/bin/sh").exists(), "requires /bin/sh")

    TestSupport.tempDirResource(s"$fixturePrefix-timeout-root-exited").use { dir =>
      val pidFile = new File(dir, "timeout-root-exited.pid")

      for {
        result <- GitProcessSupport.runCommandWithTimeout(
                    new java.lang.ProcessBuilder(
                      "/bin/sh",
                      "-c",
                      """sleep 5 & echo $! > "$0"; sleep 1""",
                      pidFile.getAbsolutePath
                    )
                      .directory(dir)
                      .redirectOutput(Redirect.DISCARD)
                      .redirectError(Redirect.DISCARD),
                    1200.millis
                  )
        _      <- waitForFile(pidFile, 5.seconds)
        pid    <- readPid(pidFile)
        alive  <- waitForProcessToExit(pid, 2.seconds)
      } yield {
        assertEquals(result, Some(0))
        assertEquals(alive, false)
      }
    }
  }

  test(
    "runCommandWithTimeout - report the exit code when the root exits before a " +
      "backgrounded helper is observable"
  ) {
    assume(new File("/bin/sh").exists(), "requires /bin/sh")

    TestSupport.tempDirResource(s"$fixturePrefix-timeout-root-exit-grace").use { dir =>
      val pidFile = new File(dir, "timeout-root-exit-grace.pid")

      Ref.of[IO, Option[Long]](None).flatMap { childPid =>
        (
          for {
            result <- GitProcessSupport.runCommandWithTimeout(
                        new java.lang.ProcessBuilder(
                          "/bin/sh",
                          "-c",
                          """sleep 5 & echo $! > "$0"""",
                          pidFile.getAbsolutePath
                        )
                          .directory(dir)
                          .redirectOutput(Redirect.DISCARD)
                          .redirectError(Redirect.DISCARD),
                        150.millis
                      )
            _      <- waitForFile(pidFile, 5.seconds)
            pid    <- readPid(pidFile)
            _      <- childPid.set(Some(pid))
          } yield {
            assert(pid > 0L)
            assertEquals(result, Some(0))
          }
        ).guarantee(
          childPid.get.flatMap {
            case Some(pid) => terminateProcess(pid)
            case None      => IO.unit
          }
        )
      }
    }
  }

  test("runCommandWithTimeout - ignore detached nohup helpers after they leave the process tree") {
    assume(new File("/bin/sh").exists(), "requires /bin/sh")
    assume(
      new File("/usr/bin/nohup").exists() || new File("/bin/nohup").exists(),
      "requires nohup"
    )

    TestSupport.tempDirResource(s"$fixturePrefix-timeout-detached-helper").use { dir =>
      val pidFile = new File(dir, "timeout-detached-helper.pid")

      Ref.of[IO, Option[Long]](None).flatMap { helperPid =>
        (
          for {
            result <- GitProcessSupport.runCommandWithTimeout(
                        new java.lang.ProcessBuilder(
                          "/bin/sh",
                          "-c",
                          """nohup sh -c 'sleep 5' >/dev/null 2>&1 & echo $! > "$0"""",
                          pidFile.getAbsolutePath
                        )
                          .directory(dir)
                          .redirectOutput(Redirect.DISCARD)
                          .redirectError(Redirect.DISCARD),
                        1500.millis
                      )
            _      <- waitForFile(pidFile, 5.seconds)
            pid    <- readPid(pidFile)
            _      <- helperPid.set(Some(pid))
            alive  <- processAlive(pid)
          } yield {
            assertEquals(result, Some(0))
            assertEquals(alive, true)
          }
        ).guarantee(
          helperPid.get.flatMap {
            case Some(pid) => terminateProcess(pid)
            case None      => IO.unit
          }
        )
      }
    }
  }

  test("runCommandWithTimeout - destroy the spawned process when the fiber is canceled") {
    assume(new File("/bin/sh").exists(), "requires /bin/sh")

    TestSupport.tempDirResource(s"$fixturePrefix-cancel").use { dir =>
      val pidFile = new File(dir, "cancel.pid")

      for {
        fiber <- GitProcessSupport
                   .runCommandWithTimeout(
                     new java.lang.ProcessBuilder(
                       "/bin/sh",
                       "-c",
                       """echo $$ > "$0"; exec sleep 5""",
                       pidFile.getAbsolutePath
                     )
                       .directory(dir)
                       .redirectOutput(Redirect.DISCARD)
                       .redirectError(Redirect.DISCARD),
                     5.seconds
                   )
                   .start
        _     <- waitForFile(pidFile, 5.seconds)
        pid   <- readPid(pidFile)
        _     <- fiber.cancel.timeout(1.second)
        alive <- waitForProcessToExit(pid, 1.second)
      } yield {
        assert(pid > 0L)
        assertEquals(alive, false)
      }
    }
  }

  private def configureAlias(
      repo: File,
      name: String,
      script: String,
      args: File*
  ): IO[Unit] = {
    val renderedArgs = args.map(file => s"'${file.getAbsolutePath}'").mkString(" ")
    val aliasValue   = s"""!sh -c '$script' $renderedArgs"""

    IO.blocking(TestSupport.runGit(repo, "config", s"alias.$name", aliasValue)).void
  }

  private def waitForFile(file: File, remaining: FiniteDuration): IO[Unit] =
    IO.blocking(file.exists()).flatMap {
      case true                                => IO.unit
      case false if remaining <= Duration.Zero =>
        IO.raiseError(new RuntimeException(s"${file.getName} did not appear in time"))
      case false                               =>
        IO.sleep(10.millis) *> waitForFile(file, remaining - 10.millis)
    }

  private def readPid(file: File): IO[Long] =
    IO.blocking(sbt.IO.read(file).trim.toLong)

  private def waitForProcessToExit(pid: Long, remaining: FiniteDuration): IO[Boolean] =
    processAlive(pid).flatMap {
      case false                              => IO.pure(false)
      case true if remaining <= Duration.Zero =>
        IO.pure(true)
      case true                               =>
        IO.sleep(10.millis) *> waitForProcessToExit(pid, remaining - 10.millis)
    }

  private def processAlive(pid: Long): IO[Boolean] =
    IO.blocking {
      val handle = java.lang.ProcessHandle.of(pid)
      handle.isPresent && handle.get.isAlive
    }

  private def terminateProcess(pid: Long): IO[Unit] =
    IO.blocking {
      val handle = java.lang.ProcessHandle.of(pid)

      if (handle.isPresent && handle.get.isAlive) {
        handle.get.destroy()
        ()
      }
    } *> waitForProcessToExit(pid, 250.millis).flatMap {
      case false => IO.unit
      case true  =>
        IO.blocking {
          val handle = java.lang.ProcessHandle.of(pid)

          if (handle.isPresent && handle.get.isAlive) {
            handle.get.destroyForcibly()
            ()
          }
        }.void *> waitForProcessToExit(pid, 250.millis).void
    }

  private final class FakeProcess(
      processId: Long,
      initialAlive: Boolean,
      descendants: Vector[FakeProcessHandle]
  ) extends Process {
    private val input  = new TrackingInputStream
    private val error  = new TrackingInputStream
    private val output = new TrackingOutputStream
    private val handle = new FakeProcessHandle(processId, initialAlive, descendants)

    var destroyCalls: Int         = 0
    var destroyForciblyCalls: Int = 0
    def inputClosed: Boolean      = input.closed
    def errorClosed: Boolean      = error.closed
    def outputClosed: Boolean     = output.closed

    override def getOutputStream(): OutputStream = output
    override def getInputStream(): InputStream   = input
    override def getErrorStream(): InputStream   = error
    override def waitFor(): Int                  = 0
    override def exitValue(): Int                = 0
    override def destroy(): Unit                 = {
      destroyCalls += 1
      handle.setAlive(false)
    }
    override def destroyForcibly(): Process      = {
      destroyForciblyCalls += 1
      handle.setAlive(false)
      this
    }
    override def isAlive(): Boolean              = handle.isAlive
    override def pid(): Long                     = processId
    override def toHandle(): ProcessHandle       = handle
  }

  private final class FakeProcessHandle(
      processId: Long,
      initialAlive: Boolean,
      descendantHandles: Vector[FakeProcessHandle] = Vector.empty
  ) extends ProcessHandle {
    private var alive = initialAlive

    var destroyCalls: Int         = 0
    var destroyForciblyCalls: Int = 0

    def setAlive(value: Boolean): Unit = alive = value

    override def pid(): Long = processId

    override def parent(): Optional[ProcessHandle] =
      Optional.empty()

    override def children(): java.util.stream.Stream[ProcessHandle] =
      Vector.empty[ProcessHandle].asJava.stream()

    override def descendants(): java.util.stream.Stream[ProcessHandle] =
      descendantHandles.map(handle => handle: ProcessHandle).asJava.stream()

    override def info(): ProcessHandle.Info = new ProcessHandle.Info {
      override def command(): Optional[String]                = Optional.empty()
      override def commandLine(): Optional[String]            = Optional.empty()
      override def arguments(): Optional[Array[String]]       = Optional.empty()
      override def startInstant(): Optional[Instant]          = Optional.empty()
      override def totalCpuDuration(): Optional[JavaDuration] = Optional.empty()
      override def user(): Optional[String]                   = Optional.empty()
    }

    override def onExit(): CompletableFuture[ProcessHandle] =
      CompletableFuture.completedFuture(this: ProcessHandle)

    override def supportsNormalTermination(): Boolean = true

    override def destroy(): Boolean = {
      destroyCalls += 1
      alive = false
      true
    }

    override def destroyForcibly(): Boolean = {
      destroyForciblyCalls += 1
      alive = false
      true
    }

    override def isAlive(): Boolean = alive

    override def compareTo(other: ProcessHandle): Int =
      java.lang.Long.compare(processId, other.pid())

    override def hashCode(): Int = java.lang.Long.hashCode(processId)

    override def equals(obj: Any): Boolean = obj match {
      case other: ProcessHandle => processId == other.pid()
      case _                    => false
    }
  }

  private final class TrackingInputStream extends ByteArrayInputStream(Array.emptyByteArray) {
    var closed: Boolean = false

    override def close(): Unit = {
      closed = true
      super.close()
    }
  }

  private final class TrackingOutputStream extends ByteArrayOutputStream {
    var closed: Boolean = false

    override def close(): Unit = {
      closed = true
      super.close()
    }
  }
}
