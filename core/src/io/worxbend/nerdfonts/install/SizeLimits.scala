package io.worxbend.nerdfonts.install

import io.worxbend.nerdfonts.http.ByteLimit

/**
 * The byte caps that bound resource use against an oversized or hostile archive (a decompression bomb, a
 * dishonest `Content-Length`). Injectable so tests can lower them without building multi-megabyte fixtures;
 * the defaults leave room for the largest known archive (`Noto.zip`, ~600 MiB).
 */
final case class SizeLimits(
    download: ByteLimit = ByteLimit.mebibytes(768),
    fontFile: ByteLimit = ByteLimit.mebibytes(128),
    archive: ByteLimit = ByteLimit.gibibytes(2),
    manifest: ByteLimit = ByteLimit.mebibytes(1),
    apiPage: ByteLimit = ByteLimit.mebibytes(8),
)

object SizeLimits:
  val default: SizeLimits = SizeLimits()
