package io.karandash.validators

import io.karandash.patterns.DefaultPatternRegistry
import io.karandash.patterns.TicketFormat
import io.karandash.testfixtures.TestPluginContext
import io.karandash.testfixtures.createTestMergeRequest
import kotlinx.coroutines.test.runTest
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertNotNull
import kotlin.test.assertTrue

class JiraTicketPatternsTest {
    private val registry = DefaultPatternRegistry().also { JiraTicketRuleModule().registerPatterns(it) }

    private fun regex(
        name: String,
        params: Map<String, String> = emptyMap(),
    ) = Regex(assertNotNull(registry.resolve(name, params), name))

    @Test
    fun `keyed patterns default to any project and narrow with the project param`() {
        for (name in listOf("jira-ticket", "linear-ticket", "youtrack-ticket")) {
            assertTrue(regex(name).matches("ABC-12"), name)
            assertFalse(regex(name).matches("abc-12"), name)
            val keyed = regex(name, mapOf("project" to "MAPS"))
            assertTrue(keyed.matches("MAPS-7"), name)
            assertFalse(keyed.matches("ABC-7"), name)
        }
    }

    @Test
    fun `fixed patterns match their own ticket style only`() {
        assertTrue(regex("github-issue").matches("#42"))
        assertFalse(regex("github-issue").matches("sc-42"))
        assertTrue(regex("shortcut-ticket").matches("sc-1234"))
        assertFalse(regex("shortcut-ticket").matches("#1234"))
        val any = regex("any-ticket")
        assertEquals(listOf(true, true, true, false), listOf("PROJ-1", "#2", "sc-3", "proj").map { any.matches(it) })
    }

    @Test
    fun `module validates through the typed config and reports a missing ticket`() =
        runTest {
            val module = JiraTicketRuleModule()
            assertTrue(module.initialize(TestPluginContext()))
            val config = JsonObject(mapOf("projectKeys" to JsonArray(listOf(JsonPrimitive("PROJ"))), "checkIn" to JsonPrimitive("title")))

            assertTrue(module.validate(JiraTicketRuleModule.RULE_ID, createTestMergeRequest(title = "PROJ-9 fix"), config).isEmpty())
            val finding =
                module
                    .validate(
                        JiraTicketRuleModule.RULE_ID,
                        createTestMergeRequest(title = "fix", description = "PROJ-9"),
                        config,
                    ).single()
            assertEquals("No JIRA ticket found in MR title.", finding.message)
            assertEquals("Add a ticket reference such as `PROJ-123` to the title.", finding.suggestion)
        }

    @Test
    fun `structured formats pick their pattern and suggestion without project keys`() {
        fun messages(
            format: TicketFormat,
            title: String,
        ) = JiraTicketValidator(JiraTicketConfig(format = format)).validate(createTestMergeRequest(title = title)).map {
            it.message to
                it.suggestion
        }

        assertTrue(messages(TicketFormat.GITHUB, "fix #12").isEmpty())
        assertEquals(
            listOf(
                "No GitHub ticket found in MR title or description." to
                    "Add a ticket reference such as `#42` to the title or description.",
            ),
            messages(TicketFormat.GITHUB, "fix"),
        )
        assertTrue(messages(TicketFormat.SHORTCUT, "sc-77 fix").isEmpty())
        assertEquals(
            listOf(
                "No Shortcut ticket found in MR title or description." to
                    "Add a ticket reference such as `sc-1234` to the title or description.",
            ),
            messages(TicketFormat.SHORTCUT, "fix"),
        )
        assertEquals(
            listOf(
                "No ticket found in MR title or description." to
                    "Add a ticket reference such as `PROJ-123` to the title or description.",
            ),
            messages(TicketFormat.CUSTOM, "fix").drop(1),
        )
        for (format in listOf(TicketFormat.LINEAR, TicketFormat.YOUTRACK)) {
            assertTrue(messages(format, "ENG-1").isEmpty())
        }
    }

    @Test
    fun `description only check ignores the title`() {
        val config = JiraTicketConfig(projectKeys = listOf("PROJ"), checkIn = JiraTicketConfig.CHECK_IN_DESCRIPTION)

        assertTrue(JiraTicketValidator(config).validate(createTestMergeRequest(title = "x", description = "see PROJ-4")).isEmpty())
        assertEquals(
            "No JIRA ticket found in MR description.",
            JiraTicketValidator(config).validate(createTestMergeRequest(title = "PROJ-4")).single().message,
        )
    }

    @Test
    fun `wrong project key message names the configured ticket format`() {
        fun message(format: TicketFormat?) =
            JiraTicketValidator(JiraTicketConfig(projectKeys = listOf("ENG"), format = format))
                .validate(createTestMergeRequest(title = "OPS-1 fix"))
                .first()
                .message

        assertEquals("Invalid Linear project key: OPS.", message(TicketFormat.LINEAR))
        assertEquals("Invalid JIRA project key: OPS.", message(null))
    }
}
