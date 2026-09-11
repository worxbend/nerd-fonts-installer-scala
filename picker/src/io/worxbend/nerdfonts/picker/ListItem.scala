package io.worxbend.nerdfonts.picker

/**
 * One row pair in a picker list. `value` is the stable identity (a release tag or a family stem); `title`
 * changes with the selection marker, which is why toggling a family replaces items rather than mutating them.
 */
final case class ListItem(title: String, description: String, value: String):
  /** What the fuzzy filter searches, in Go's `FilterValue` shape. */
  def filterValue: String = s"$title $description $value"
