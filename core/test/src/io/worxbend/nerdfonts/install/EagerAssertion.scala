package io.worxbend.nerdfonts.install

import zio.test.TestResult

/**
 * Forces a `TestResult`'s lazily-built assertion trace immediately, before a `ZIO.acquireReleaseWith`-managed
 * temp directory elsewhere in this package can be cleaned up.
 *
 * `TestResult.result` is a `lazy val`: zio-test's own reporting layer only forces it once the whole spec has
 * finished running, which is later than a workspace fixture's `release`, which fires as soon as the
 * `TestResult` *value* is produced by `use`, not once its internals are evaluated. Left unforced, an
 * `assertTrue` that reads the workspace back (`os.read`, `os.list`, `os.exists`) can race its own cleanup and
 * fail with a spurious `NoSuchFileException` instead of a real assertion failure.
 */
private[install] object EagerAssertion:
  def eagerly(result: TestResult): TestResult =
    val _ = result.result
    result
