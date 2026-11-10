package io.karandash.validators

import io.karandash.models.support.WellKnownMetadataKeys
import io.karandash.patterns.TicketFormat
import io.karandash.testfixtures.createTestMergeRequest
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNotNull
import kotlin.test.assertTrue

class JiraTicketFixHintTest {
    private fun findings(
        config: JiraTicketConfig,
        title: String,
    ) = JiraTicketValidator(config).validate(createTestMergeRequest(title = title, description = null))

    @Test
    fun `missing ticket states the problem and suggests a ticket of the configured project`() {
        val finding = findings(
            JiraTicketConfig(projectKeys = listOf("ENG", "OPS"), checkIn = JiraTicketConfig.CHECK_IN_TITLE),
            "fix"
        ).single()

        assertEquals("No JIRA ticket found in MR title.", finding.message)
        assertEquals("Add a ticket reference such as `ENG-123` to the title.", finding.suggestion)
        val format = assertNotNull(finding.metadata?.get(WellKnownMetadataKeys.EXPECTED_FORMAT))
        assertTrue(format.contains("ENG") && format.contains("OPS"), format)
    }

    @Test
    fun `structured formats suggest their own ticket shape`() {
        assertEquals(
            "Add a ticket reference such as `#42` to the title or description.",
            findings(JiraTicketConfig(format = TicketFormat.GITHUB), "fix").single().suggestion,
        )
    }

    @Test
    fun `wrong project key keeps the ticket number in the suggestion`() {
        val finding = findings(JiraTicketConfig(projectKeys = listOf("ENG")), "OPS-7 fix").single()

        assertEquals("Invalid JIRA project key: OPS.", finding.message)
        assertEquals("Use a ticket of an allowed project, e.g. `ENG-7` instead of `OPS-7`.", finding.suggestion)
    }
}
