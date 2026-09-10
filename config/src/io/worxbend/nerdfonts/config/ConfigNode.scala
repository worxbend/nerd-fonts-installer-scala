package io.worxbend.nerdfonts.config

/**
 * The format-neutral tree that [[ConfigFieldDecoder]] reads.
 *
 * The two file formats disagree only about what a leaf may mean: YAML `yes` is both the text `yes` and the
 * boolean true, JSON `"true"` is only text, JSON `true` is only a boolean and a JSON number is neither. Each
 * adapter settles those readings while building the tree, so the field decoder stays strict and identical for
 * both formats. The `Boolean` here is the literal's own reading, not a domain value; it becomes a
 * `RefreshFontCache` at the field boundary.
 */
private[config] enum ConfigNode:
  case Null
  case Scalar(asText: Option[String], asBoolean: Option[Boolean])
  case Sequence(items: Vector[ConfigNode])
  case Mapping(fields: Vector[(String, ConfigNode)])
