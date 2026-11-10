package io.karandash.platforms.gitlab

import kotlinx.coroutines.test.runTest
import kotlin.test.Test
import kotlin.test.assertEquals

/** Committable suggestions on a line range use GitLab's `suggestion:-N+0` form. */
class GitLabSuggestionRangeTest {
    private val body = "**WARNING**\n\n```suggestion\nval x = 1\nval y = 2\n```"

    @Test
    fun `range comment widens the suggestion to the lines above the anchor`() {
        val result = GitLabPlatformService.multiLineSuggestions(body, line = 12, startLine = 10)

        assertEquals("**WARNING**\n\n```suggestion:-2+0\nval x = 1\nval y = 2\n```", result)
    }

    @Test
    fun `single-line comment keeps the plain suggestion fence`() {
        assertEquals(body, GitLabPlatformService.multiLineSuggestions(body, line = 12, startLine = null))
        assertEquals(body, GitLabPlatformService.multiLineSuggestions(body, line = 12, startLine = 12))
    }

    @Test
    fun `rendered block uses the fence the range rewrite matches`() =
        runTest {
            val block = GitLabPlatformService().renderSuggestionBlock("val x = 1")

            assertEquals("```suggestion:-1+0\nval x = 1\n```", GitLabPlatformService.multiLineSuggestions(block, 5, 4))
        }
}
