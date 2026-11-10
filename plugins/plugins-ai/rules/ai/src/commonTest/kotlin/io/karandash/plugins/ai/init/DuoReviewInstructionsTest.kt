package io.karandash.plugins.ai.init

import io.karandash.logger.Logger
import io.karandash.plugins.ai.core.ReviewInstruction
import io.karandash.plugins.parsers.yaml.YamlFormatModule
import kotlinx.coroutines.test.runTest
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue

/**
 * `.gitlab/duo/mr-review-instructions.yaml`, as read from the MR base, is parsed into [ReviewInstruction]s; a bad
 * entry or file is warned about and left out, and the file is capped in size and entries.
 */
class DuoReviewInstructionsTest {
    private val logger = RecordingLogger()

    private suspend fun parse(text: String?): List<ReviewInstruction> = DuoReviewInstructions.parse(text, YamlFormatModule(), logger)

    @Test
    fun `a complete entry is read with its file filters and text`() =
        runTest {
            val loaded = parse(GOOD)

            assertEquals(listOf(ReviewInstruction("Ruby", listOf("*.rb", "!spec/**"), "Check for N+1 queries\n")), loaded)
            assertTrue(logger.warnings.isEmpty(), "unexpected warnings: ${logger.warnings}")
        }

    @Test
    fun `an entry without file filters is dropped with a warning`() =
        runTest {
            val loaded = parse(GOOD + "  - name: Broken\n    instructions: no filters here\n")

            assertEquals(listOf("Ruby"), loaded.map { it.name })
            assertTrue(logger.warnings.any { "Broken" in it }, "dropped entry not named: ${logger.warnings}")
        }

    @Test
    fun `a file absent from the target branch yields no instructions and only a debug line`() =
        runTest {
            assertTrue(parse(null).isEmpty())
            assertTrue(logger.warnings.isEmpty(), "absent file warned about: ${logger.warnings}")
            assertTrue(logger.debugs.any { "target branch" in it }, "absent file not logged: ${logger.debugs}")
        }

    @Test
    fun `invalid yaml yields no instructions and a warning`() =
        runTest {
            val loaded = parse("instructions: [unclosed\n  - : :")

            assertTrue(loaded.isEmpty())
            assertTrue(logger.warnings.isNotEmpty(), "invalid file not warned about")
        }

    @Test
    fun `an oversized file is ignored with a warning`() =
        runTest {
            val loaded = parse(GOOD + "# " + "x".repeat(DuoReviewInstructions.MAX_BYTES) + "\n")

            assertTrue(loaded.isEmpty())
            assertTrue(logger.warnings.any { "KB" in it }, "oversized file not warned about: ${logger.warnings}")
        }

    @Test
    fun `entries past the cap are dropped with a warning`() =
        runTest {
            val entries = (1..DuoReviewInstructions.MAX_ENTRIES + 5).joinToString("") {
                "  - name: R$it\n    fileFilters: [\"*.rb\"]\n    instructions: check $it\n"
            }

            val loaded = parse("instructions:\n$entries")

            assertEquals((1..DuoReviewInstructions.MAX_ENTRIES).map { "R$it" }, loaded.map { it.name })
            assertTrue(logger.warnings.any { "5" in it }, "dropped entries not counted: ${logger.warnings}")
        }

    private class RecordingLogger : Logger {
        val warnings = mutableListOf<String>()
        val debugs = mutableListOf<String>()

        override fun debug(message: String) {
            debugs += message
        }

        override fun info(message: String) = Unit

        override fun warn(message: String) {
            warnings += message
        }

        override fun error(message: String) = Unit
    }

    private companion object {
        val GOOD =
            """
            |instructions:
            |  - name: Ruby
            |    fileFilters: ["*.rb", "!spec/**"]
            |    instructions: |
            |      Check for N+1 queries
            |
            """.trimMargin()
    }
}
