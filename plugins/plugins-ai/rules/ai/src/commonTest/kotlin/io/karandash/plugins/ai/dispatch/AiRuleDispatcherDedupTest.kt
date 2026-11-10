package io.karandash.plugins.ai.dispatch

import io.karandash.logger.Logger
import io.karandash.models.finding.Finding
import io.karandash.models.finding.Location
import io.karandash.models.finding.Severity
import io.karandash.plugins.ai.buildTestRegistry
import io.karandash.plugins.ai.core.AiFindingMetadataKeys
import kotlin.test.Test
import kotlin.test.assertEquals

class AiRuleDispatcherDedupTest {
    private val logger = object : Logger {
        override fun debug(message: String) = Unit

        override fun info(message: String) = Unit

        override fun warn(message: String) = Unit

        override fun error(message: String) = Unit
    }

    private val dispatcher = AiRuleDispatcher(buildTestRegistry(), logger)

    private fun finding(
        message: String = "m",
        severity: Severity = Severity.WARNING,
        location: Location? = Location.FileRef("A.kt", 3),
        category: String? = null,
    ) = Finding(
        severity = severity,
        message = message,
        rule = "r",
        location = location,
        metadata = listOfNotNull(category?.let { AiFindingMetadataKeys.CATEGORY to it }).toMap(),
    )

    @Test
    fun `identical findings from overlapping chunks collapse keeping the highest severity`() {
        val result = dispatcher.deduplicateChunkFindings(
            listOf(finding(severity = Severity.INFO), finding(severity = Severity.ERROR)),
        )
        assertEquals(listOf(Severity.ERROR), result.map { it.severity })
    }

    @Test
    fun `same location with different message stays separate`() {
        assertEquals(2, dispatcher.deduplicateChunkFindings(listOf(finding("a"), finding("b"))).size)
    }

    @Test
    fun `same location and message with different category stays separate`() {
        val result = dispatcher.deduplicateChunkFindings(listOf(finding(category = "bug"), finding(category = "style")))
        assertEquals(2, result.size)
    }

    @Test
    fun `findings without a file location are never collapsed`() {
        val result = dispatcher.deduplicateChunkFindings(listOf(finding(location = null), finding(location = null)))
        assertEquals(2, result.size)
    }
}
