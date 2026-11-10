package io.karandash.plugins.ai.core.config

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNull
import kotlin.test.assertTrue

class EnvironmentTrackResolverTest {
    @Test
    fun `no matching track returns null`() {
        val tracks = listOf(TrackConfig(name = "prod", targetBranchPattern = "^main$"))
        assertNull(EnvironmentTrackResolver.resolve("feature/my-feature", tracks))
    }

    @Test
    fun `empty tracks list returns null`() {
        assertNull(EnvironmentTrackResolver.resolve("main", emptyList()))
    }

    @Test
    fun `main branch matches production track`() {
        val tracks = listOf(TrackConfig(name = "production", targetBranchPattern = "^(main|master)$"))
        val result = EnvironmentTrackResolver.resolve("main", tracks)
        assertEquals("production", result?.name)
    }

    @Test
    fun `master branch matches production track`() {
        val tracks = listOf(TrackConfig(name = "production", targetBranchPattern = "^(main|master)$"))
        val result = EnvironmentTrackResolver.resolve("master", tracks)
        assertEquals("production", result?.name)
    }

    @Test
    fun `feature branch matches feature track`() {
        val tracks = listOf(
            TrackConfig(name = "production", targetBranchPattern = "^main$"),
            TrackConfig(name = "feature", targetBranchPattern = "^feature/"),
        )
        val result = EnvironmentTrackResolver.resolve("feature/my-work", tracks)
        assertEquals("feature", result?.name)
    }

    @Test
    fun `first matching track wins`() {
        val tracks = listOf(
            TrackConfig(name = "first", targetBranchPattern = "main"),
            TrackConfig(name = "second", targetBranchPattern = "main"),
        )
        val result = EnvironmentTrackResolver.resolve("main", tracks)
        assertEquals("first", result?.name)
    }

    @Test
    fun `malformed regex pattern is skipped`() {
        val tracks = listOf(
            TrackConfig(name = "bad", targetBranchPattern = "[invalid("),
            TrackConfig(name = "good", targetBranchPattern = "main"),
        )
        val result = EnvironmentTrackResolver.resolve("main", tracks)
        assertEquals("good", result?.name)
    }

    @Test
    fun `filterRules with enableRules keeps only matching rules`() {
        val track = TrackConfig(targetBranchPattern = "main", enableRules = listOf("security", "approval"))
        val all = setOf("title-format", "security", "approval", "draft")
        val result = EnvironmentTrackResolver.filterRules(track, all)
        assertEquals(setOf("security", "approval"), result)
    }

    @Test
    fun `filterRules with empty enableRules keeps all rules`() {
        val track = TrackConfig(targetBranchPattern = "main")
        val all = setOf("title-format", "security", "draft")
        val result = EnvironmentTrackResolver.filterRules(track, all)
        assertEquals(all, result)
    }

    @Test
    fun `filterRules with disableRules removes matching rules`() {
        val track = TrackConfig(targetBranchPattern = "feature/", disableRules = listOf("security", "approval"))
        val all = setOf("title-format", "security", "approval", "draft")
        val result = EnvironmentTrackResolver.filterRules(track, all)
        assertEquals(setOf("title-format", "draft"), result)
    }

    @Test
    fun `filterRules applies enable then disable`() {
        val track = TrackConfig(
            targetBranchPattern = "main",
            enableRules = listOf("ai-"),
            disableRules = listOf("ai-security"),
        )
        val all = setOf("title-format", "ai-review", "ai-security", "draft")
        val result = EnvironmentTrackResolver.filterRules(track, all)
        assertEquals(setOf("ai-review"), result)
    }

    @Test
    fun `filterRules returns empty set when enableRules matches nothing`() {
        val track = TrackConfig(targetBranchPattern = "main", enableRules = listOf("nonexistent"))
        val all = setOf("title-format", "commit-messages")
        val result = EnvironmentTrackResolver.filterRules(track, all)
        assertTrue(result.isEmpty())
    }

    @Test
    fun `track pattern uses containsMatchIn not full match`() {
        val tracks = listOf(TrackConfig(name = "release", targetBranchPattern = "release"))
        val result = EnvironmentTrackResolver.resolve("release/1.0.0", tracks)
        assertEquals("release", result?.name)
    }
}
