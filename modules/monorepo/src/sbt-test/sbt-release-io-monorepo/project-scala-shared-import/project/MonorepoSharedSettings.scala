import _root_.io.release.ReleaseSharedPlugin.autoImport.*
import sbt.*

object MonorepoSharedSettings {

  val sharedSettings: Seq[Setting[?]] = Seq(
    releaseIOVcsIgnoreUntrackedFiles := true
  )
}
