package io.worxbend.nerdfonts.picker

import scala.annotation.tailrec

/**
 * Case-insensitive subsequence matching, ranked by where the match starts and how far it spreads — an
 * approximation of `sahilm/fuzzy`, which bubbles' list uses. The match positions are kept so the view can
 * underline them; they index into the searched text, which begins with the item title.
 *
 * Case folding is done per character so positions stay aligned with the original text; `String.toLowerCase`
 * may change the length for a handful of scripts.
 */
private[picker] object FuzzyMatcher:
  /** Positions of the matched pattern characters in the text; empty for an empty pattern. */
  final case class Match(positions: Vector[Int]):
    /** Earlier and tighter matches rank first. */
    def rank: (Int, Int) = positions.headOption.fold((0, 0))(first => (first, positions.last - first))

  def matchIn(pattern: String, text: String): Option[Match] =
    if pattern.isEmpty then Some(Match(Vector.empty))
    else subsequence(fold(pattern), fold(text), 0, 0, Vector.empty).map(Match(_))

  /** Every item the pattern matches, in rank order; ties keep their original order. */
  def rank[A](pattern: String, items: Vector[A])(text: A => String): Vector[(A, Match)] =
    items.flatMap(item => matchIn(pattern, text(item)).map(item -> _)).sortBy(_._2.rank)

  private def fold(text: String): String = text.map(Character.toLowerCase)

  @tailrec
  private def subsequence(
      pattern: String,
      text: String,
      patternIndex: Int,
      textIndex: Int,
      found: Vector[Int],
  ): Option[Vector[Int]] =
    if patternIndex == pattern.length then Some(found)
    else if textIndex == text.length then None
    else if pattern.charAt(patternIndex) == text.charAt(textIndex) then
      subsequence(pattern, text, patternIndex + 1, textIndex + 1, found :+ textIndex)
    else subsequence(pattern, text, patternIndex, textIndex + 1, found)
