package io.worxbend.nerdfonts.config

/**
 * Which decoder a file gets. Decided by the last extension alone, like Go's `filepath.Ext` compared
 * case-insensitively with `.json`: `x.JSON` is JSON, `x.json.bak`, `.conf`, `.yml` and an extension-less file
 * are YAML. YAML is the fallback rather than an error so `.conf` keeps working as the Go tool documents.
 */
private[config] enum ConfigFormat:
  case Yaml, Json

private[config] object ConfigFormat:
  private val jsonExtension = ".json"

  def of(path: os.Path): ConfigFormat =
    if goExtension(path.last).equalsIgnoreCase(jsonExtension) then Json else Yaml

  // `filepath.Ext` keeps the dot and treats a leading dot as an extension (`.json` is JSON), unlike `os.Path.ext`.
  private def goExtension(fileName: String): String = fileName.lastIndexOf('.') match
    case -1    => ""
    case index => fileName.substring(index)
