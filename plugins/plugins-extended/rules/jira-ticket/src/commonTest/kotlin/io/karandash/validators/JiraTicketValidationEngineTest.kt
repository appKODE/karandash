package io.karandash.validators

import io.karandash.config.Config
import io.karandash.config.RulesConfig
import io.karandash.models.finding.Severity
import io.karandash.testfixtures.createTestEngine
import io.karandash.testfixtures.createTestMergeRequest
import io.karandash.testfixtures.toJsonElement
import kotlinx.coroutines.test.runTest
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue

class JiraTicketValidationEngineTest {
    @Test
    fun `validateJiraTicket with empty projectKeys list accepts any ticket`() =
        runTest {
            val config = Config(
                rules = RulesConfig(
                    ruleConfigs = mapOf(
                        "jiraTicket" to JiraTicketConfig(projectKeys = emptyList(), pattern = ".*").toJsonElement()
                    )
                )
            )
            val mr = createTestMergeRequest(title = "TEST-123 feature")
            val engine = createTestEngine(config, mapOf("jiraTicket" to true))
            val result = engine.validate(mr)

            // An empty projectKeys list means no key restriction.
            assertTrue(result.findings.isEmpty())
        }

    @Test
    fun `validateJiraTicket with ticket from wrong project produces error`() =
        runTest {
            val config = Config(
                rules = RulesConfig(
                    ruleConfigs = mapOf(
                        "jiraTicket" to JiraTicketConfig(
                            projectKeys = listOf("PROJ", "TEAM"),
                            pattern = "([A-Z]+-\\d+)"
                        ).toJsonElement()
                    )
                )
            )
            val mr = createTestMergeRequest(title = "WRONG-123: feature")
            val engine = createTestEngine(config, mapOf("jiraTicket" to true))
            val result = engine.validate(mr)

            assertEquals(1, result.findings.size)
            assertEquals(Severity.ERROR, result.findings[0].severity)
            assertEquals("jiraTicket", result.findings[0].rule)
            assertTrue(result.findings[0].message.contains("WRONG"))
            assertTrue(result.findings[0].message.contains("Invalid JIRA project key"))
        }

    @Test
    fun `validateJiraTicket with no ticket in title produces error`() =
        runTest {
            val config = Config(
                rules = RulesConfig(
                    ruleConfigs = mapOf(
                        "jiraTicket" to JiraTicketConfig(
                            projectKeys = listOf("PROJ"),
                            pattern = "([A-Z]+-\\d+)"
                        ).toJsonElement()
                    )
                )
            )
            val mr = createTestMergeRequest(title = "feature without ticket")
            val engine = createTestEngine(config, mapOf("jiraTicket" to true))
            val result = engine.validate(mr)

            assertEquals(1, result.findings.size)
            assertEquals(Severity.ERROR, result.findings[0].severity)
            assertTrue(result.findings[0].message.contains("No JIRA ticket found"))
        }

    @Test
    fun `validateJiraTicket with valid ticket from correct project passes`() =
        runTest {
            val config = Config(
                rules = RulesConfig(
                    ruleConfigs = mapOf(
                        "jiraTicket" to JiraTicketConfig(
                            projectKeys = listOf("PROJ"),
                            pattern = "([A-Z]+-\\d+)"
                        ).toJsonElement()
                    )
                )
            )
            val mr = createTestMergeRequest(title = "PROJ-123: feature")
            val engine = createTestEngine(config, mapOf("jiraTicket" to true))
            val result = engine.validate(mr)

            assertTrue(result.findings.isEmpty())
        }

    @Test
    fun `validateJiraTicket with multiple valid project keys`() =
        runTest {
            val config = Config(
                rules = RulesConfig(
                    ruleConfigs = mapOf(
                        "jiraTicket" to JiraTicketConfig(
                            projectKeys = listOf("PROJ", "TEAM", "WORK"),
                            pattern = "([A-Z]+-\\d+)"
                        ).toJsonElement()
                    )
                )
            )

            val mr1 = createTestMergeRequest(title = "PROJ-123: feature")
            val mr2 = createTestMergeRequest(title = "TEAM-456: feature")
            val mr3 = createTestMergeRequest(title = "WORK-789: feature")

            val engine = createTestEngine(config, mapOf("jiraTicket" to true))

            assertTrue(engine.validate(mr1).findings.isEmpty())
            assertTrue(engine.validate(mr2).findings.isEmpty())
            assertTrue(engine.validate(mr3).findings.isEmpty())
        }

    @Test
    fun `validateJiraTicket with ticket in middle of title`() =
        runTest {
            val config = Config(
                rules = RulesConfig(
                    ruleConfigs = mapOf(
                        "jiraTicket" to JiraTicketConfig(
                            projectKeys = listOf("PROJ"),
                            pattern = "([A-Z]+-\\d+)"
                        ).toJsonElement()
                    )
                )
            )
            val mr = createTestMergeRequest(title = "feat: PROJ-123 add feature")
            val engine = createTestEngine(config, mapOf("jiraTicket" to true))
            val result = engine.validate(mr)

            assertTrue(result.findings.isEmpty())
        }

    @Test
    fun `validateJiraTicket with custom pattern`() =
        runTest {
            val config = Config(
                rules = RulesConfig(
                    ruleConfigs = mapOf(
                        "jiraTicket" to JiraTicketConfig(
                            projectKeys = listOf("CUSTOM"),
                            pattern = "(CUSTOM-[0-9]{4})" // Custom pattern: CUSTOM-1234 (with dash)
                        ).toJsonElement()
                    )
                )
            )
            val mr = createTestMergeRequest(title = "CUSTOM-1234: feature")
            val engine = createTestEngine(config, mapOf("jiraTicket" to true))
            val result = engine.validate(mr)

            assertTrue(result.findings.isEmpty())
        }

    @Test
    fun `validateJiraTicket with null pattern uses default pattern`() =
        runTest {
            val config = Config(
                rules = RulesConfig(
                    ruleConfigs = mapOf(
                        "jiraTicket" to JiraTicketConfig(projectKeys = listOf("PROJ")).toJsonElement()
                    )
                )
            )
            val mr = createTestMergeRequest(title = "PROJ-123: feature")
            val engine = createTestEngine(config, mapOf("jiraTicket" to true))
            val result = engine.validate(mr)

            assertTrue(result.findings.isEmpty())
        }

    @Test
    fun `validateJiraTicket with invalid regex pattern returns error finding`() =
        runTest {
            val config = Config(
                rules = RulesConfig(
                    ruleConfigs = mapOf(
                        "jiraTicket" to JiraTicketConfig(
                            projectKeys = listOf("PROJ"),
                            pattern = "[invalid((" // Invalid regex
                        ).toJsonElement()
                    )
                )
            )
            val mr = createTestMergeRequest(title = "PROJ-123: feature")
            val engine = createTestEngine(config, mapOf("jiraTicket" to true))

            val result = engine.validate(mr)
            assertEquals(1, result.findings.size)
            assertEquals(Severity.ERROR, result.findings[0].severity)
            assertEquals("jiraTicket", result.findings[0].rule)
            assertTrue(result.findings[0].message.contains("Invalid regex pattern"))
        }
}
