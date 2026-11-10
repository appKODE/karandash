package io.karandash.plugins.ai.core.diagnostics

import io.karandash.api.platform.ChargeResult
import io.karandash.logger.Logger
import kotlinx.serialization.Serializable
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue

class ContextQualityDiagnosticsTest {
    private class RecordingLogger(
        private val context: Map<String, String> = emptyMap(),
    ) : Logger {
        val events: MutableList<LogEvent> = mutableListOf()

        override val isDebugEnabled: Boolean get() = true

        override fun debug(message: String) {
            events.add(LogEvent("debug", message, context))
        }

        override fun info(message: String) {
            events.add(LogEvent("info", message, context))
        }

        override fun warn(message: String) {
            events.add(LogEvent("warn", message, context))
        }

        override fun error(message: String) {
            events.add(LogEvent("error", message, context))
        }

        override fun withContext(
            key: String,
            value: String
        ): Logger {
            val next = RecordingLogger(context + (key to value))
            next.events.apply {
                clear()
                addAll(events)
            }
            return RedirectedLogger(this, context + (key to value))
        }
    }

    /**
     * Forwards events into the parent so the test can assert against a single log.
     * Preserves the accumulated context so assertions can check per-event keys.
     */
    private class RedirectedLogger(
        private val parent: RecordingLogger,
        private val context: Map<String, String>,
    ) : Logger {
        override fun debug(message: String) = parent.events.add(LogEvent("debug", message, context)).let {}

        override fun info(message: String) = parent.events.add(LogEvent("info", message, context)).let {}

        override fun warn(message: String) = parent.events.add(LogEvent("warn", message, context)).let {}

        override fun error(message: String) = parent.events.add(LogEvent("error", message, context)).let {}

        override fun withContext(
            key: String,
            value: String
        ): Logger = RedirectedLogger(parent, context + (key to value))
    }

    @Serializable
    private data class LogEvent(
        val level: String,
        val message: String,
        val context: Map<String, String>,
    )

    @Test
    fun `subprocessStart emits debug with source tag`() {
        val logger = RecordingLogger()
        val diagnostics = ContextQualityDiagnostics(logger)

        diagnostics.subprocessStart("local-git", listOf("git", "log", "--format"), "/tmp/repo")

        assertEquals(1, logger.events.size)
        val event = logger.events.single()
        assertEquals("debug", event.level)
        assertEquals("context-quality", event.context["source"])
        assertEquals("subprocess-start", event.context["event"])
        assertEquals("local-git", event.context["provider"])
        assertEquals("git", event.context["command"])
        assertEquals("2", event.context["argCount"])
        assertEquals("/tmp/repo", event.context["workingDir"])
    }

    @Test
    fun `providerSkipped emits info`() {
        val logger = RecordingLogger()
        val diagnostics = ContextQualityDiagnostics(logger)

        diagnostics.providerSkipped("convention-context", SkipReason.SHALLOW_CLONE)

        val event = logger.events.single()
        assertEquals("info", event.level)
        assertEquals("shallow-clone", event.context["reason"])
        assertEquals("convention-context", event.context["provider"])
    }

    @Test
    fun `findingDropped emits warn`() {
        val logger = RecordingLogger()
        val diagnostics = ContextQualityDiagnostics(logger)

        diagnostics.findingDropped("review-comments", "src/Foo.kt", 42, FindingDropReason.NOT_CHANGED)

        val event = logger.events.single()
        assertEquals("warn", event.level)
        assertEquals("review-comments", event.context["rule"])
        assertEquals("src/Foo.kt", event.context["path"])
        assertEquals("42", event.context["line"])
        assertEquals("not-changed", event.context["reason"])
    }

    @Test
    fun `findingDropped tolerates null path and line`() {
        val logger = RecordingLogger()
        val diagnostics = ContextQualityDiagnostics(logger)

        diagnostics.findingDropped("review-comments", null, null, FindingDropReason.PATH_UNKNOWN)

        val event = logger.events.single()
        assertEquals("", event.context["path"])
        assertEquals("", event.context["line"])
    }

    @Test
    fun `budgetUnderflow emits warn`() {
        val logger = RecordingLogger()
        val diagnostics = ContextQualityDiagnostics(logger)

        diagnostics.budgetUnderflow(totalBudget = 500, fileCount = 10, minFloor = 200)

        val event = logger.events.single()
        assertEquals("warn", event.level)
        assertEquals("500", event.context["totalBudget"])
        assertEquals("10", event.context["fileCount"])
        assertEquals("200", event.context["minFloor"])
    }

    @Test
    fun `budgetRejected only warns on firstWarning rejection`() {
        val logger = RecordingLogger()
        val diagnostics = ContextQualityDiagnostics(logger)

        diagnostics.budgetRejected(
            "caller-finder",
            ChargeResult.RejectedCount(head = "git", used = 31, limit = 30, firstWarning = true),
        )
        diagnostics.budgetRejected(
            "caller-finder",
            ChargeResult.RejectedCount(head = "git", used = 32, limit = 30, firstWarning = false),
        )

        assertEquals(1, logger.events.size)
        val event = logger.events.single()
        assertEquals("warn", event.level)
        assertEquals("call-count", event.context["reason"])
    }

    @Test
    fun `budgetRejected skips accepted results silently`() {
        val logger = RecordingLogger()
        val diagnostics = ContextQualityDiagnostics(logger)

        diagnostics.budgetRejected(
            "caller-finder",
            ChargeResult.Accepted("git", 100, 29, 29_000),
        )

        assertTrue(logger.events.isEmpty())
    }

    @Test
    fun `blockIncluded and blockSkipped share source tag`() {
        val logger = RecordingLogger()
        val diagnostics = ContextQualityDiagnostics(logger)

        diagnostics.blockIncluded("related-files", "Related files for Auth.kt", 200, 1_200, 3_800)
        diagnostics.blockSkipped("full-file-context", "Full file", 6_000, 500, BlockSkipReason.OVER_BUDGET)

        assertEquals(2, logger.events.size)
        assertEquals("debug", logger.events[0].level)
        assertEquals("info", logger.events[1].level)
        assertEquals("context-quality", logger.events[0].context["source"])
        assertEquals("context-quality", logger.events[1].context["source"])
    }
}
