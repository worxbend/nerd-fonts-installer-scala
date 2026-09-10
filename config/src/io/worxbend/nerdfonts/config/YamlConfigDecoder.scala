package io.worxbend.nerdfonts.config

import java.util.Locale

import org.virtuslab.yaml.Node
import org.virtuslab.yaml.Tag
import org.virtuslab.yaml.YamlError
import org.virtuslab.yaml.parseYaml

/**
 * Reads a YAML config file into a [[ConfigDocument]] with the scalar rules of Go's yaml.v3 decoding into typed
 * fields: any non-null scalar is text where text is expected (`families: [3270]` names a real family), a null
 * scalar leaves the key unset, and the `true/false/yes/no/on/off/y/n` family of words is a boolean whether quoted
 * or not. Tags are deliberately ignored except for null: what the user typed matters, not what YAML would infer.
 */
object YamlConfigDecoder:
  private val trueWords  = Set("true", "yes", "on", "y")
  private val falseWords = Set("false", "no", "off", "n")

  def decode(path: os.Path, text: String): Either[ConfigError, ConfigDocument] =
    // scala-yaml reports "no node" for an empty stream; §4 defines it as a document with every key absent.
    if text.trim.isEmpty then Right(ConfigDocument.empty)
    else
      parseYaml(text).left
        .map(error => ConfigError.Parse(path, describe(error)))
        .map(node)
        .flatMap(ConfigFieldDecoder.decode(path, _))

  private def node(yaml: Node): ConfigNode = yaml match
    case scalar: Node.ScalarNode     =>
      if scalar.tag == Tag.nullTag then ConfigNode.Null
      else ConfigNode.Scalar(Some(scalar.value), booleanReading(scalar.value))
    case sequence: Node.SequenceNode => ConfigNode.Sequence(sequence.nodes.toVector.map(node))
    case mapping: Node.MappingNode   =>
      // scala-yaml hands mappings back in document order and keeps repeated keys as separate entries, which is
      // what lets the field decoder report a duplicate the way yaml.v3 does.
      ConfigNode.Mapping(mapping.mappings.toVector.map((key, value) => keyText(key) -> node(value)))

  private def keyText(key: Node): String = key match
    case scalar: Node.ScalarNode => scalar.value
    case _                       => "[complex key]"

  private def booleanReading(text: String): Option[Boolean] = text.toLowerCase(Locale.ROOT) match
    case word if trueWords(word)  => Some(true)
    case word if falseWords(word) => Some(false)
    case _                        => None

  // scala-yaml messages span several lines (a caret drawing under the offending text); one stderr line is
  // easier to read and to prefix.
  private def describe(error: YamlError): String =
    error.msg.linesIterator.map(_.trim).filter(_.nonEmpty).mkString(" ")
