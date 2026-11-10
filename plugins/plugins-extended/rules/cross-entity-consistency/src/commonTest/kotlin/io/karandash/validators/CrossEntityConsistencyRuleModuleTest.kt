package io.karandash.validators

import io.karandash.models.Commit
import io.karandash.models.CommitAuthor
import io.karandash.models.finding.Severity
import io.karandash.testfixtures.createTestMergeRequest
import kotlinx.coroutines.test.runTest
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonElement
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertTrue

class CrossEntityConsistencyRuleModuleTest {
    private val module = CrossEntityConsistencyRuleModule()

    private fun commit(message: String) = Commit("abc", message, CommitAuthor("dev", "dev@example.com"), "2024-01-01T00:00:00Z")

    private fun json(text: String): JsonElement = Json.parseToJsonElement(text)

    @Test
    fun `module is opt-in and explains its rule`() =
        runTest {
            assertFalse(module.defaultEnabled)
            assertEquals(setOf(CrossEntityConsistencyRuleModule.RULE_ID), module.contributedRules())
            assertTrue(checkNotNull(module.ruleExplanation(CrossEntityConsistencyRuleModule.RULE_ID)).startsWith("Ensures consistency"))
        }

    @Test
    fun `fully populated json config drives coverage and text-matched duplicate checks`() =
        runTest {
            val config =
                json(
                    """
                    {"taskIdPattern": "T-\\d+", "mrTaskSource": "both", "commitTasksSubsetOfMr": false,
                     "changelogConsistency": true, "changelogDuplicateDetection": true, "changelogPrefix": "NOTE:",
                     "changelogDuplicateRequiresMatchingText": true, "changelogCoverage": true,
                     "ticketFormat": "jira", "ticketProjects": ["X"]}
                    """.trimIndent(),
                )
            val mr =
                createTestMergeRequest(
                    title = "T-1 fix",
                    description = "also T-2",
                    commits = listOf(commit("T-9 stray\n\nNOTE: T-1 done"), commit("T-1 more\n\nNOTE: T-1 done")),
                )

            val findings = module.validate(CrossEntityConsistencyRuleModule.RULE_ID, mr, config)

            assertEquals(
                listOf(
                    "MR title/description ticket(s) not covered by any CHANGELOG entry: T-2",
                    "Duplicate task IDs found in CHANGELOG entries: T-1",
                ),
                findings.map { it.message },
            )
        }

    @Test
    fun `description source with no description treats every commit task as extra`() =
        runTest {
            val mr = createTestMergeRequest(title = "ABC-1 work", description = null, commits = listOf(commit("ABC-1 work")))

            val finding = module
                .validate(
                    CrossEntityConsistencyRuleModule.RULE_ID,
                    mr,
                    json("""{"mrTaskSource": "description"}""")
                ).single()

            assertEquals("Commit abc mentions ABC-1, but the MR description has no task ID.", finding.message)
            assertEquals("Add [ABC-1] to the MR description or remove it from the commit subject.", finding.suggestion)
        }

    @Test
    fun `coverage stays quiet when no changelog entry exists`() =
        runTest {
            val mr = createTestMergeRequest(title = "ABC-1 work", commits = listOf(commit("ABC-1 work")))

            assertTrue(module.validate(CrossEntityConsistencyRuleModule.RULE_ID, mr, json("""{"changelogCoverage": true}""")).isEmpty())
        }

    @Test
    fun `unknown task source falls back to defaults with an info finding`() =
        runTest {
            val mr = createTestMergeRequest(title = "ABC-1 work", commits = listOf(commit("ABC-2 work")))

            val findings = module.validate(CrossEntityConsistencyRuleModule.RULE_ID, mr, json("""{"mrTaskSource": "nope"}"""))

            assertEquals(2, findings.size)
            assertEquals("Commit abc mentions ABC-2, which is not in the MR title (ABC-1).", findings[0].message)
            assertEquals(Severity.INFO, findings[1].severity)
            assertTrue(findings[1].message.startsWith("Invalid config for rule 'crossEntityConsistency', using defaults:"))
        }

    @Test
    fun `invalid task pattern yields only the pattern error`() =
        runTest {
            val mr = createTestMergeRequest(title = "ABC-1", commits = listOf(commit("ABC-2")))

            val finding = module.validate(CrossEntityConsistencyRuleModule.RULE_ID, mr, json("""{"taskIdPattern": "("}""")).single()

            assertEquals(CrossEntityConsistencyRuleModule.RULE_ID, finding.rule)
            assertEquals(Severity.ERROR, finding.severity)
        }
}
