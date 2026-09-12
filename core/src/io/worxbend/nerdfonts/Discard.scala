package io.worxbend.nerdfonts

/**
 * A one-line replacement for Ox's `ox.discard`: marks a non-`Unit` value as deliberately unused, so
 * `-Wvalue-discard` does not flag a statement whose result is thrown away on purpose (a mutation's return
 * value, an `AtomicLong` counter bump, a `Files.write` result kept only for its side effect).
 */
extension (value: Any) def discard: Unit = ()
