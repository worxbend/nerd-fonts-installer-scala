package io.worxbend.nerdfonts.config

import zio.Scope
import zio.ZIO

/** A scoped temporary directory for the file-based config suites; removed however the test ends. */
private[config] object TempDir:
  def scoped(prefix: String): ZIO[Scope, Nothing, os.Path] = ZIO
    .acquireRelease(ZIO.attemptBlockingIO(os.temp.dir(prefix = prefix)).orDie)(dir =>
      ZIO.attemptBlockingIO(os.remove.all(dir)).orDie,
    )
