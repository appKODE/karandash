package io.karandash.cli.commands.config

import com.github.ajalt.clikt.testing.test
import kotlin.test.Test
import kotlin.test.assertContains
import kotlin.test.assertEquals
import kotlin.test.assertNotEquals
import kotlin.test.assertTrue

class ConfigDescribeCommandTest {
    @Test
    fun `prints description for known key`() {
        val command = ConfigDescribeCommand()
        val result = command.test(listOf("rules"))

        assertEquals(0, result.statusCode)
        assertContains(result.output, "rules:")
        assertContains(result.output, "Rule configurations")
    }

    @Test
    fun `prints description for timeouts key`() {
        val command = ConfigDescribeCommand()
        val result = command.test(listOf("timeouts"))

        assertEquals(0, result.statusCode)
        assertContains(result.output, "timeouts:")
        assertContains(result.output, "Timeout configuration")
    }

    @Test
    fun `prints description for report key`() {
        val result = ConfigDescribeCommand().test(listOf("report"))

        assertEquals(0, result.statusCode)
        assertContains(
            result.output,
            "report:\n  Report preferences: language (BCP-47 tag, default en), transparency.summary (off, collapsed, expanded; " +
                "default collapsed), transparency.inline-why (default false), transparency.flow-diagram (default false), " +
                "walkthrough (off, collapsed; default off), style (quiet, standard, verbose; default standard).",
        )
    }

    @Test
    fun `prints error for unknown key`() {
        val command = ConfigDescribeCommand()
        val result = command.test(listOf("nonexistent"))

        assertNotEquals(0, result.statusCode)
    }

    @Test
    fun `all description keys have non-empty values`() {
        for ((key, description) in ConfigDescribeCommand.DESCRIPTIONS) {
            assertTrue(key.isNotBlank(), "Key should not be blank")
            assertTrue(description.isNotBlank(), "Description for '$key' should not be blank")
        }
    }
}
