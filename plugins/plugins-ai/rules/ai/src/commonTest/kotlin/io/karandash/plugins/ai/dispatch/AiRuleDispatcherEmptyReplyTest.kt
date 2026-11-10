package io.karandash.plugins.ai.dispatch

import io.karandash.models.finding.Finding
import io.karandash.models.finding.Severity
import io.karandash.models.support.WellKnownMetadataKeys
import io.karandash.sdk.Logger
import kotlin.test.Test
import kotlin.test.assertEquals

class AiRuleDispatcherEmptyReplyTest {
    private class WarnLogger : Logger {
        val warnings = mutableListOf<String>()

        override val isDebugEnabled: Boolean get() = false

        override fun debug(message: String) = Unit

        override fun info(message: String) = Unit

        override fun warn(message: String) {
            warnings.add(message)
        }

        override fun error(message: String) = Unit
    }

    private fun parseError(length: String) =
        Finding(
            severity = Severity.INFO,
            message = "unparseable response",
            rule = "review-comments",
            metadata = mapOf(WellKnownMetadataKeys.ERROR to "parse_error", "rawResponseLength" to length),
        )

    @Test
    fun `empty reply notice is logged and dropped while other parse errors stay`() {
        val logger = WarnLogger()
        val real = parseError("120")
        val kept = dropEmptyReplyNotices(listOf(parseError("0"), real), "review-comments", logger)
        assertEquals(listOf(real), kept)
        assertEquals(1, logger.warnings.size)
    }
}
