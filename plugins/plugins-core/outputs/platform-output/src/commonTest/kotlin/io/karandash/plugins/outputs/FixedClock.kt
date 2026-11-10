package io.karandash.plugins.outputs

import kotlin.time.Clock
import kotlin.time.Instant

/** A clock stopped at [at], so the `updated …` line of a posted comment is known. */
internal class FixedClock(
    private val at: Instant = Instant.parse("2026-10-30T15:40:12Z"),
) : Clock {
    override fun now(): Instant = at
}

/** The inline `updated …` line [FixedClock] gives by default. */
internal const val STAMP = "\n\n<sub>updated 2026-10-30 15:40 UTC</sub>"
