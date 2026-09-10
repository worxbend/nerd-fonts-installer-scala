package io.worxbend.nerdfonts.config

/** Writes config fixtures into a suite's temporary directory. */
private[config] object ConfigFiles:
  def write(dir: os.Path, name: String, text: String): os.Path =
    val path = dir / os.RelPath(name)
    os.write(path, text, createFolders = true)
    path
