package io.karandash.validators

import io.karandash.models.Commit
import io.karandash.models.CommitAuthor
import io.karandash.testfixtures.TestPluginContext
import io.karandash.testfixtures.createTestMergeRequest
import kotlinx.coroutines.test.runTest
import kotlinx.serialization.json.JsonElement
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue

class CommitBodyRuleModuleTest {
    private fun mr(
        title: String,
        vararg messages: String,
    ) = createTestMergeRequest(
        title = title,
        commits =
            messages.mapIndexed { i, m ->
                Commit(sha = "abcdef$i", message = m, author = CommitAuthor("A", "a@x.io"), timestamp = "2026-01-01T00:00:00Z")
            },
    )

    private suspend fun messages(
        config: Map<String, JsonElement>,
        title: String,
        vararg commits: String,
    ): List<String> {
        val module = CommitBodyRuleModule()
        module.initialize(TestPluginContext())
        return module.validate(CommitBodyRuleModule.RULE_ID, mr(title, *commits), JsonObject(config)).map { it.message }
    }

    @Test
    fun `any commit scope with title filter and any line match reads every key from json`() =
        runTest {
            val config =
                mapOf(
                    "pattern" to JsonPrimitive("^CHANGELOG: .+"),
                    "required" to JsonPrimitive(false),
                    "requiredWhenSubjectMatches" to JsonPrimitive("^feat"),
                    "requiredWhenTitleMatches" to JsonPrimitive("RELEASE"),
                    "matchMode" to JsonPrimitive("anyLine"),
                    "scope" to JsonPrimitive("anyCommit"),
                )

            assertTrue(messages(config, "RELEASE 1", "fix: a\n\nintro\nCHANGELOG: fixed a", "chore: b").isEmpty())
            assertEquals(
                listOf("No commit body in this MR contains a line matching the configured pattern."),
                messages(config, "RELEASE 1", "fix: a\n\nintro", "chore: b"),
            )
            assertTrue(messages(config, "small", "fix: a", "chore: b").isEmpty())
        }

    @Test
    fun `invalid required when filters are reported instead of silently ignored`() =
        runTest {
            val found =
                messages(
                    mapOf("requiredWhenSubjectMatches" to JsonPrimitive("(feat"), "requiredWhenTitleMatches" to JsonPrimitive("[x")),
                    "t",
                    "feat: a",
                )

            assertEquals(2, found.size, found.toString())
            assertTrue(found[0].contains("(feat"), found[0])
            assertTrue(found[1].contains("[x"), found[1])
        }

    @Test
    fun `module explains only its own rule`() =
        runTest {
            val module = CommitBodyRuleModule()
            val explanation = checkNotNull(module.ruleExplanation(CommitBodyRuleModule.RULE_ID))
            assertTrue(explanation.startsWith("Commit bodies should follow a structured format"), explanation)
            assertEquals(null, module.ruleExplanation("other"))
        }
}
