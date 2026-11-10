package io.karandash.validators

import io.karandash.models.Commit
import io.karandash.models.CommitAuthor
import io.karandash.patterns.DelimiterType
import io.karandash.testfixtures.createTestMergeRequest
import kotlin.test.Test
import kotlin.test.assertEquals

class ContextTagKeyBehaviourTest {
    private fun count(
        config: ContextTagConfig,
        title: String,
        commitMessage: String? = null,
    ): Int {
        val commits = commitMessage?.let { listOf(Commit("abc1234", it, CommitAuthor("u", "e"), "2024-01-01T00:00:00Z")) }.orEmpty()
        return ContextTagValidator(config).validate(createTestMergeRequest(title = title, commits = commits)).size
    }

    @Test
    fun `allowed flags a title without an allowed tag`() {
        val config = ContextTagConfig(allowed = listOf("api"))
        assertEquals(1, count(config, "[web] change"))
        assertEquals(0, count(config, "[api] change"))
    }

    @Test
    fun `exactlyOne flags a title with several tags`() {
        val title = "[api][web] change"
        assertEquals(1, count(ContextTagConfig(allowed = listOf("api", "web"), exactlyOne = true), title))
        assertEquals(0, count(ContextTagConfig(allowed = listOf("api", "web"), exactlyOne = false), title))
    }

    @Test
    fun `checkMrTitle false skips the MR title`() {
        assertEquals(1, count(ContextTagConfig(allowed = listOf("api"), checkMrTitle = true, checkCommitTitles = false), "change"))
        assertEquals(0, count(ContextTagConfig(allowed = listOf("api"), checkMrTitle = false, checkCommitTitles = false), "change"))
    }

    @Test
    fun `checkCommitTitles false skips the commit titles`() {
        val title = "[api] change"
        assertEquals(1, count(ContextTagConfig(allowed = listOf("api"), checkCommitTitles = true), title, "no tag"))
        assertEquals(0, count(ContextTagConfig(allowed = listOf("api"), checkCommitTitles = false), title, "no tag"))
    }

    @Test
    fun `tagPattern replaces the default tag syntax`() {
        val config = ContextTagConfig(allowed = listOf("api"), tagPattern = "#([a-z]+)")
        assertEquals(0, count(config, "#api change"))
        assertEquals(1, count(config, "[api] change"))
    }

    @Test
    fun `caseSensitive makes tag matching exact`() {
        assertEquals(0, count(ContextTagConfig(allowed = listOf("api"), caseSensitive = false), "[API] change"))
        assertEquals(1, count(ContextTagConfig(allowed = listOf("api"), caseSensitive = true), "[API] change"))
    }

    @Test
    fun `delimiter selects the tag brackets`() {
        val config = ContextTagConfig(allowed = listOf("api"), delimiter = DelimiterType.PARENS)
        assertEquals(0, count(config, "(api) change"))
        assertEquals(1, count(config, "[api] change"))
    }
}
