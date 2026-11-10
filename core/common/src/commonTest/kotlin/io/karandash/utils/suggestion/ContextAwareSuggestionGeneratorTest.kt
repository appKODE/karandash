package io.karandash.utils.suggestion

import kotlin.test.Test
import kotlin.test.assertEquals

class ContextAwareSuggestionGeneratorTest {
    // --- suggestTitleWithTicket ---

    @Test
    fun `suggestTitleWithTicket extracts ticket from branch`() {
        val result = ContextAwareSuggestionGenerator.suggestTitleWithTicket(
            actualTitle = "add new feature",
            sourceBranch = "feature/PROJ-123-add-login",
        )
        assertEquals("Change the title to `PROJ-123: add new feature`.", result)
    }

    @Test
    fun `suggestTitleWithTicket falls back when no ticket in branch`() {
        val result = ContextAwareSuggestionGenerator.suggestTitleWithTicket(
            actualTitle = "add new feature",
            sourceBranch = "feature/add-login",
        )
        assertEquals("Add a ticket reference to the title, e.g. `PROJ-123: add new feature`.", result)
    }

    @Test
    fun `suggestTitleWithTicket with blank title uses generic fallback`() {
        val result = ContextAwareSuggestionGenerator.suggestTitleWithTicket(
            actualTitle = "",
            sourceBranch = "feature/PROJ-456-fix",
        )
        assertEquals("Add a ticket reference to the title, e.g. `PROJ-123: <title>`.", result)
    }

    // --- suggestConventionalCommit ---

    @Test
    fun `suggestConventionalCommit infers fix type from message`() {
        val types = listOf("feat", "fix", "docs", "chore")
        val result = ContextAwareSuggestionGenerator.suggestConventionalCommit(
            actualMessage = "fix login bug on mobile",
            conventionalTypes = types,
        )
        assertEquals("Change the commit message to `fix: login bug on mobile`.", result)
    }

    @Test
    fun `suggestConventionalCommit infers feat type from add keyword`() {
        val types = listOf("feat", "fix", "docs", "chore")
        val result = ContextAwareSuggestionGenerator.suggestConventionalCommit(
            actualMessage = "add new dashboard widget",
            conventionalTypes = types,
        )
        assertEquals("Change the commit message to `feat: add new dashboard widget`.", result)
    }

    @Test
    fun `suggestConventionalCommit infers docs type`() {
        val types = listOf("feat", "fix", "docs", "chore")
        val result = ContextAwareSuggestionGenerator.suggestConventionalCommit(
            actualMessage = "update documentation for API",
            conventionalTypes = types,
        )
        assertEquals("Change the commit message to `docs: update documentation for API`.", result)
    }

    @Test
    fun `suggestConventionalCommit defaults to first type when no match`() {
        val types = listOf("feat", "fix", "docs")
        val result = ContextAwareSuggestionGenerator.suggestConventionalCommit(
            actualMessage = "some random change",
            conventionalTypes = types,
        )
        assertEquals("Change the commit message to `feat: some random change`.", result)
    }

    @Test
    fun `suggestConventionalCommit strips existing conventional prefix`() {
        val types = listOf("feat", "fix", "docs", "chore")
        val result = ContextAwareSuggestionGenerator.suggestConventionalCommit(
            actualMessage = "wrongtype: added login feature",
            conventionalTypes = types,
        )
        assertEquals("Change the commit message to `feat: added login feature`.", result)
    }

    @Test
    fun `suggestConventionalCommit handles blank message`() {
        val types = listOf("feat", "fix")
        val result = ContextAwareSuggestionGenerator.suggestConventionalCommit(
            actualMessage = "",
            conventionalTypes = types,
        )
        assertEquals("Follow the conventional commit format, e.g. `feat: describe your change`.", result)
    }

    @Test
    fun `suggestConventionalCommit uses custom types`() {
        val types = listOf("feature", "bugfix", "hotfix")
        val result = ContextAwareSuggestionGenerator.suggestConventionalCommit(
            actualMessage = "bug in login page",
            conventionalTypes = types,
        )
        assertEquals("Change the commit message to `bugfix: bug in login page`.", result)
    }

    @Test
    fun `suggestConventionalCommit falls back to first custom type when no keyword match`() {
        val types = listOf("feature", "bugfix", "hotfix")
        val result = ContextAwareSuggestionGenerator.suggestConventionalCommit(
            actualMessage = "login page broken",
            conventionalTypes = types,
        )
        assertEquals("Change the commit message to `feature: login page broken`.", result)
    }
}
