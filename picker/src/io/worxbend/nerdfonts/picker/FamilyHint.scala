package io.worxbend.nerdfonts.picker

/** The one-line blurb under each family, copied from Go `familyHint`: lower-case the name, first match wins. */
private[picker] object FamilyHint:
  def of(family: String): String =
    val key = family.toLowerCase
    if key.contains("mono") then "monospace favorite"
    else if key.contains("code") then "coding ligatures"
    else if key.contains("symbol") then "glyph toolkit"
    else "Nerd Font patched"
