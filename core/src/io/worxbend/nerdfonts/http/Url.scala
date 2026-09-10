package io.worxbend.nerdfonts.http

/**
 * A URL as text. Deliberately unvalidated: every URL in this application is assembled from a constant base
 * and percent-escaped segments, so a malformed one is a defect that the adapter reports as
 * `HttpError.Transport` rather than a case callers need to handle. The type exists to stop arbitrary strings
 * from being passed where a URL is expected.
 */
opaque type Url = String

object Url:
  def apply(value: String): Url = value

  extension (url: Url)
    /** The URL text, for requests and messages. */
    def value: String = url
