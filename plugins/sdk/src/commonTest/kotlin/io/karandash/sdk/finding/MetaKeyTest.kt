package io.karandash.sdk.finding

import io.karandash.api.ExperimentalKarandashApi
import io.karandash.models.finding.Finding
import io.karandash.models.finding.Severity
import kotlinx.serialization.Serializable
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNull

@OptIn(ExperimentalKarandashApi::class)
class MetaKeyTest {
    @Serializable
    data class Score(
        val score: Int,
        val reason: String,
    )

    private val key = MetaKey("test.score", Score.serializer())
    private val base = Finding(severity = Severity.INFO, message = "m", rule = "r")

    @Test
    fun roundTripsTypedValueThroughStringMetadata() {
        val finding = base.withMeta(key, Score(7, "why"))

        assertEquals(Score(7, "why"), finding.meta(key))
        assertEquals("""{"score":7,"reason":"why"}""", finding.metadata["test.score"])
    }

    @Test
    fun missingKeyReadsAsNull() {
        assertNull(base.meta(key))
    }

    @Test
    fun withMetaKeepsExistingMetadata() {
        val finding = base.copy(metadata = mapOf("a" to "b")).withMeta(key, Score(1, "x"))

        assertEquals("b", finding.metadata["a"])
    }
}
