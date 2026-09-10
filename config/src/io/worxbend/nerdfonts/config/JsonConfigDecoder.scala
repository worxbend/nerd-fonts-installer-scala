package io.worxbend.nerdfonts.config

import io.worxbend.nerdfonts.Diagnostics

import ox.either.catching

/**
 * Reads a JSON config file into a [[ConfigDocument]] with the strictness of Go's `encoding/json`: a string
 * position accepts only a string, the boolean accepts only `true`/`false`, `null` means "not set", and anything
 * after the first value is `multiple json values` (Go decodes a second value to detect this; ujson refuses
 * trailing content, which we report the same way).
 */
object JsonConfigDecoder:
  private val trailingContentClue = "expected whitespace or eof"
  private val multipleValues      = "multiple json values"

  def decode(path: os.Path, text: String): Either[ConfigError, ConfigDocument] =
    parse(path, text).map(node).flatMap(ConfigFieldDecoder.decode(path, _))

  private def parse(path: os.Path, text: String): Either[ConfigError, ujson.Value] = ujson
    .read(text, trace = false)
    .catching[ujson.ParsingFailedException]
    .left
    .map(error => ConfigError.Parse(path, message(error)))

  private def message(error: ujson.ParsingFailedException): String = error match
    case parse: ujson.ParseException if parse.clue.startsWith(trailingContentClue) => multipleValues
    case other                                                                     => Diagnostics.describe(other)

  private def node(json: ujson.Value): ConfigNode = json match
    case ujson.Null       => ConfigNode.Null
    case text: ujson.Str  => ConfigNode.Scalar(Some(text.value), None)
    case flag: ujson.Bool => ConfigNode.Scalar(None, Some(flag.value))
    case _: ujson.Num     => ConfigNode.Scalar(None, None)
    case array: ujson.Arr => ConfigNode.Sequence(array.value.toVector.map(element))
    case obj: ujson.Obj   => ConfigNode.Mapping(obj.value.toVector.map((key, value) => key -> node(value)))

  // encoding/json stores `null` into a `[]string` element as the zero string, which then fails validation as an
  // empty family name; yaml.v3 drops such an entry instead, so the substitution belongs here, not in the shared
  // decoder.
  private def element(json: ujson.Value): ConfigNode = json match
    case ujson.Null => ConfigNode.Scalar(Some(""), None)
    case other      => node(other)
