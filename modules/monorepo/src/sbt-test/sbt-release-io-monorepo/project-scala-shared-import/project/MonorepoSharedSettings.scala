import _root_.io.release.ReleasePluginIO.autoImport.*
import sbt.*

object MonorepoSharedSettings {

  val sharedSettings: Seq[Setting[?]] = Seq(
    releaseIOVcsIgnoreUntrackedFiles := true
  )
}
