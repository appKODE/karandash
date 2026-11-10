package io.karandash.cli.errors

import io.karandash.utils.SuggestionEngine
import kotlin.test.Test
import kotlin.test.assertEquals

class SuggestionEngineTest {
    @Test
    fun `an id in another notation suggests the canonical id`() {
        val candidates = listOf("jira-ticket", "commit-message")
        assertEquals(listOf("jira-ticket"), SuggestionEngine.suggest("JIRA_TICKET", candidates))
        assertEquals("jira-ticket", SuggestionEngine.closest("jiraTicket", candidates))
        assertEquals("jira-ticket", SuggestionEngine.closest("jira_tiket", candidates, maxDistance = 2))
        assertEquals(null, SuggestionEngine.closest("unrelated", candidates))
    }

    @Test
    fun `exact match distance 0`() {
        val result = SuggestionEngine.suggest("openai", listOf("openai", "anthropic"))
        assertEquals(listOf("openai"), result)
    }

    @Test
    fun `single char typo distance 1`() {
        val result = SuggestionEngine.suggest("opnai", listOf("openai", "anthropic"))
        assertEquals(listOf("openai"), result)
    }

    @Test
    fun `missing char distance 1`() {
        val result = SuggestionEngine.suggest("opeai", listOf("openai"))
        assertEquals(listOf("openai"), result)
    }

    @Test
    fun `extra char distance 1`() {
        val result = SuggestionEngine.suggest("oppenai", listOf("openai"))
        assertEquals(listOf("openai"), result)
    }

    @Test
    fun `transposition distance 2`() {
        val result = SuggestionEngine.suggest("opneai", listOf("openai"))
        assertEquals(listOf("openai"), result)
    }

    @Test
    fun `multiple candidates closest first`() {
        val result = SuggestionEngine.suggest("gtihub", listOf("github", "gitlab", "bitbucket"))
        assertEquals(listOf("github"), result)
    }

    @Test
    fun `no suggestions distance greater than 3`() {
        val result = SuggestionEngine.suggest("xyz", listOf("openai", "anthropic"))
        assertEquals(emptyList(), result)
    }

    @Test
    fun `case insensitivity`() {
        val result = SuggestionEngine.suggest("OPENAI", listOf("openai"))
        assertEquals(listOf("openai"), result)
    }

    @Test
    fun `empty input`() {
        val result = SuggestionEngine.suggest("", listOf("openai"))
        // Distance from "" to "openai" is 6, which is > 3, so no suggestions
        assertEquals(emptyList(), result)
    }

    @Test
    fun `empty candidates`() {
        val result = SuggestionEngine.suggest("openai", emptyList())
        assertEquals(emptyList(), result)
    }

    @Test
    fun `max 3 suggestions`() {
        val candidates = listOf("github", "gitflow", "gitpod", "gitlab")
        val result = SuggestionEngine.suggest("gitable", candidates)
        // Should return max 3 closest suggestions with distance <= 3
        assertTrue(result.size <= 3)
        // Verify all returned candidates have distance <= 3
        result.forEach { candidate ->
            val distance = levenshteinDistance("gitable", candidate.lowercase())
            assertTrue(distance <= 3)
        }
    }

    // Helper function for testing distance
    private fun levenshteinDistance(
        s1: String,
        s2: String
    ): Int {
        val len1 = s1.length
        val len2 = s2.length
        val dp = Array(len1 + 1) { IntArray(len2 + 1) }

        for (i in 0..len1) dp[i][0] = i
        for (j in 0..len2) dp[0][j] = j

        for (i in 1..len1) {
            for (j in 1..len2) {
                val cost = if (s1[i - 1] == s2[j - 1]) 0 else 1
                dp[i][j] = minOf(dp[i - 1][j] + 1, dp[i][j - 1] + 1, dp[i - 1][j - 1] + cost)
            }
        }
        return dp[len1][len2]
    }

    private fun assertTrue(condition: Boolean) {
        if (!condition) {
            throw AssertionError("Assertion failed")
        }
    }
}
