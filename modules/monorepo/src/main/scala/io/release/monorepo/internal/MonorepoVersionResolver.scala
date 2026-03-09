package io.release.monorepo.internal

import cats.effect.IO
import io.release.monorepo.{MonorepoRuntime, MonorepoVersionFiles}
import sbt.{File, ProjectRef, State}

/** Resolves monorepo version-file inputs from the current sbt state. */
private[monorepo] object MonorepoVersionResolver {

  final case class ResolvedProjectVersion(
      versionFile: File,
      readVersion: File => IO[String],
      writeVersion: (File, String) => IO[String],
      useGlobalVersion: Boolean
  )

  def resolve(state: State, ref: ProjectRef): IO[ResolvedProjectVersion] =
    IO.blocking {
      val runtime = MonorepoRuntime.fromState(state)
      ResolvedProjectVersion(
        versionFile = MonorepoVersionFiles.resolve(runtime, ref),
        readVersion = runtime.readVersion,
        writeVersion = runtime.writeVersion,
        useGlobalVersion = runtime.useGlobalVersion
      )
    }

  def sessionSettings(state: State): IO[Seq[sbt.Setting[?]]] =
    IO.blocking {
      val runtime      = MonorepoRuntime.fromState(state)
      Seq(
        _root_.io.release.monorepo.MonorepoReleaseIO.releaseIOMonorepoVersionFile      :=
          runtime.extracted.get(
            _root_.io.release.monorepo.MonorepoReleaseIO.releaseIOMonorepoVersionFile
          ),
        _root_.io.release.monorepo.MonorepoReleaseIO.releaseIOMonorepoReadVersion      :=
          runtime.readVersion,
        _root_.io.release.monorepo.MonorepoReleaseIO.releaseIOMonorepoWriteVersion     :=
          runtime.writeVersion,
        _root_.io.release.monorepo.MonorepoReleaseIO.releaseIOMonorepoUseGlobalVersion :=
          runtime.useGlobalVersion
      )
    }
}
