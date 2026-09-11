package io.worxbend.nerdfonts.http

/** A GET request: the URL plus any headers beyond the adapter's defaults. */
final case class HttpRequest(url: Url, headers: Map[String, String] = Map.empty)

object HttpRequest:
  /**
   * The product identity string: the one place it is spelled, so `JdkHttpClient`'s default and any caller
   * that sends it explicitly (`GitHubReleaseCatalogue`, which the Accept header sits next to) cannot drift.
   */
  val userAgent: String = "nerd-fonts-installer"
