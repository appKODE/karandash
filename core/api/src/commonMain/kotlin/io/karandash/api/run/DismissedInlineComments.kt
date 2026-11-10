package io.karandash.api.run

import io.karandash.api.ExperimentalKarandashApi

/** Reads the run MR's dismissed inline comments; see [RunServiceKeys.DISMISSED_INLINE_COMMENTS]. */
@ExperimentalKarandashApi
public fun interface DismissedInlineComments {
    /** Bodies of the dismissed comments; `null` when the platform has no dismissal signal. */
    public suspend fun list(): List<String>?
}
