package io.karandash.validators

import io.karandash.patterns.TicketFormat
import io.karandash.testfixtures.createTestMergeRequest
import kotlinx.serialization.json.JsonNull
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue

class JiraTicketKeyBehaviourTest {
    private fun messages(
        config: JiraTicketConfig,
        title: String,
        description: String? = null,
        tickets: JsonObject? = null,
    ): List<String> =
        JiraTicketValidator(config, tickets = tickets)
            .validate(createTestMergeRequest(title = title, description = description))
            .map { it.message }

    private fun details(
        status: String,
        assignee: String?,
    ) = JsonObject(
        mapOf(
            "ABC-1" to
                JsonObject(
                    mapOf(
                        "status" to JsonPrimitive(status),
                        "assignee" to (assignee?.let { JsonPrimitive(it) } ?: JsonNull),
                    ),
                ),
        ),
    )

    @Test
    fun `projectKeys flags a ticket of another project`() {
        val config = JiraTicketConfig(projectKeys = listOf("ABC"))
        assertEquals(1, messages(config, "XYZ-1 change").size)
        assertEquals(0, messages(config, "ABC-1 change").size)
    }

    @Test
    fun `projects replaces projectKeys`() {
        val config = JiraTicketConfig(projectKeys = listOf("ABC"), projects = listOf("XYZ"))
        assertEquals(0, messages(config, "XYZ-1 change").size)
        assertEquals(1, messages(config, "ABC-1 change").size)
    }

    @Test
    fun `pattern replaces the default ticket shape`() {
        val config = JiraTicketConfig(pattern = "TASK#\\d+")
        assertEquals(0, messages(config, "TASK#12 change").size)
        assertEquals(1, messages(config, "ABC-1 change").size)
    }

    @Test
    fun `format selects the ticket shape`() {
        val config = JiraTicketConfig(format = TicketFormat.GITHUB)
        assertEquals(0, messages(config, "fix #12").size)
        assertEquals(1, messages(config, "ABC-1 change").size)
    }

    @Test
    fun `checkIn limits where the ticket is searched`() {
        val title = JiraTicketConfig(checkIn = JiraTicketConfig.CHECK_IN_TITLE)
        val description = JiraTicketConfig(checkIn = JiraTicketConfig.CHECK_IN_DESCRIPTION)
        assertEquals(1, messages(title, "change", "ABC-1").size)
        assertEquals(0, messages(description, "change", "ABC-1").size)
        assertEquals(1, messages(description, "ABC-1 change", "text").size)
    }

    @Test
    fun `validateStatus flags a ticket in a status outside the list`() {
        val config = JiraTicketConfig(validateStatus = listOf("In Review"), jiraUrl = "https://jira.example.com")
        assertTrue(messages(config, "ABC-1 x", tickets = details("Open", "bob")).single().contains("status 'Open'"))
        assertEquals(0, messages(config, "ABC-1 x", tickets = details("In Review", "bob")).size)
    }

    @Test
    fun `validateAssignee flags a ticket without an assignee`() {
        val config = JiraTicketConfig(validateAssignee = true, jiraUrl = "https://jira.example.com")
        assertTrue(messages(config, "ABC-1 x", tickets = details("Open", null)).single().contains("no assignee"))
        assertEquals(0, messages(config, "ABC-1 x", tickets = details("Open", "bob")).size)
    }

    @Test
    fun `jiraUrl decides whether missing Jira data is reported`() {
        val without = JiraTicketConfig(validateStatus = listOf("Done"))
        val with = without.copy(jiraUrl = "https://jira.example.com")
        assertTrue(messages(without, "ABC-1 x").single().contains("jiraUrl is not set"))
        assertTrue(messages(with, "ABC-1 x").single().contains("No Jira data"))
    }
}
