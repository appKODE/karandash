package io.karandash.validators

import io.karandash.models.Commit
import io.karandash.models.CommitAuthor
import io.karandash.testfixtures.TestPluginContext
import io.karandash.testfixtures.createTestMergeRequest
import kotlinx.coroutines.test.runTest
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonElement
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue

class CommitMessagesRuleModuleTest {
    private fun mr(vararg messages: String) =
        createTestMergeRequest(
            commits =
                messages.mapIndexed { i, m ->
                    Commit(sha = "abcdef$i", message = m, author = CommitAuthor("A", "a@x.io"), timestamp = "2026-01-01T00:00:00Z")
                },
        )

    private suspend fun messages(
        config: Map<String, JsonElement>,
        vararg commits: String,
    ): List<String> {
        val module = CommitMessagesRuleModule()
        module.initialize(TestPluginContext())
        return module.validate(CommitMessagesRuleModule.RULE_ID, mr(*commits), JsonObject(config)).map { it.message }
    }

    @Test
    fun `structured type and subject config apply the conventional check and subject length`() =
        runTest {
            val config =
                mapOf(
                    "type" to JsonObject(mapOf("allowed" to JsonArray(listOf(JsonPrimitive("feat"))))),
                    "subject" to JsonObject(mapOf("maxLength" to JsonPrimitive(12))),
                )

            assertTrue(messages(config, "feat: short").isEmpty())
            assertEquals(
                listOf(
                    "Commit message does not follow conventional format: abcdef0",
                    "Commit abcdef0 subject is 15 chars (limit 12).",
                ),
                messages(config, "fix: too long x"),
            )
        }

    @Test
    fun `invalid pattern and unknown format are reported once per MR`() =
        runTest {
            val found = messages(mapOf("pattern" to JsonPrimitive("(unclosed"), "format" to JsonPrimitive("gitmoji")), "a", "b")

            assertEquals(2, found.size)
            assertTrue(found[0].contains("(unclosed"), found[0])
            assertEquals("Unknown commit format: gitmoji", found[1])
            assertTrue(messages(mapOf("pattern" to JsonPrimitive("(unclosed"))).isEmpty())
        }

    @Test
    fun `min length reports the short first line`() =
        runTest {
            assertEquals(
                listOf("Commit abcdef0 subject is 2 chars (minimum 5)."),
                messages(mapOf("minLength" to JsonPrimitive(5)), "ok\n\nlong body text", "long enough"),
            )
        }

    @Test
    fun `long subjects across many commits fold into one finding`() =
        runTest {
            assertEquals(
                listOf("4 commits have a subject over 5 chars (abcdef0, abcdef1, abcdef2, +1 more)"),
                messages(mapOf("maxLength" to JsonPrimitive(5)), "long one", "long two", "long three", "long four"),
            )
        }

    @Test
    fun `length findings name the limit in the suggestion`() =
        runTest {
            val module = CommitMessagesRuleModule()
            module.initialize(TestPluginContext())
            val config = JsonObject(mapOf("minLength" to JsonPrimitive(5), "maxLength" to JsonPrimitive(12)))
            val suggestions =
                module.validate(CommitMessagesRuleModule.RULE_ID, mr("ok", "feat: add the login"), config).map { it.suggestion }

            assertEquals(
                listOf(
                    "Expand it to at least 5 characters.",
                    "Shorten it; move details to the body.",
                ),
                suggestions,
            )
        }
}
