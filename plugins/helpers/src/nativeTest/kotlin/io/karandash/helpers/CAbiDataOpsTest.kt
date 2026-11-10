@file:OptIn(ExperimentalForeignApi::class)

package io.karandash.helpers

import io.karandash.api.platform.UnsupportedPlatformOperationException
import kotlinx.cinterop.ExperimentalForeignApi
import kotlinx.cinterop.toKString
import platform.posix.free
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNull

/** The K/N guest answers a query whose SDK default throws with the unsupported marker, not a crash or NULL. */
class CAbiDataOpsTest {
    @Test
    fun queryAnswersTheUnsupportedMarkerForTheThrowingSdkDefault() {
        val answer = checkNotNull(CAbiDataOps.query { throw UnsupportedPlatformOperationException("searchMergeRequests") })
        assertEquals("""{"err":"searchMergeRequests is not supported by this platform","unsupported":true}""", answer.toKString())
        free(answer)
    }

    @Test
    fun queryKeepsNullAndDataAnswers() {
        assertNull(CAbiDataOps.query { null })
        val answer = checkNotNull(CAbiDataOps.query { "[]" })
        assertEquals("[]", answer.toKString())
        free(answer)
    }
}
