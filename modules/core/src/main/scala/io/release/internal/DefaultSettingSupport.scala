package io.release.internal

import sbt.Setting

private[release] object DefaultSettingSupport {

  def combine(blocks: Seq[Setting[?]]*): Seq[Setting[?]] =
    blocks.iterator.flatten.toVector
}
