package io.worxbend.nerdfonts.fonts

/** Whether `fc-cache` runs after a successful install; a two-case enum so call sites cannot pass a bare flag. */
enum RefreshFontCache:
  case Enabled, Disabled

object RefreshFontCache:
  /** The boundary conversion for decoders that receive a genuine boolean (YAML / JSON). */
  def fromBoolean(value: Boolean): RefreshFontCache = if value then Enabled else Disabled
