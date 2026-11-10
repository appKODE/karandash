package io.karandash.plugins.ai.core.config

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertNotNull
import kotlin.test.assertNull
import kotlin.test.assertTrue

class ExecutionProfileResolverTest {
    @Test
    fun `null profile returns null`() {
        assertNull(ExecutionProfileResolver.resolve(null))
    }

    @Test
    fun `unknown profile returns null`() {
        assertNull(ExecutionProfileResolver.resolve("nonexistent"))
    }

    @Test
    fun `built-in ci profile has AI disabled`() {
        val profile = ExecutionProfileResolver.resolve("ci")
        assertNotNull(profile)
        assertFalse(profile.aiEnabled)
    }

    @Test
    fun `built-in ci profile excludes ai rules`() {
        val profile = ExecutionProfileResolver.resolve("ci")
        assertNotNull(profile)
        assertTrue(profile.excludeRules.any { it.contains("ai-") })
    }

    @Test
    fun `built-in ci profile exits on error`() {
        val profile = ExecutionProfileResolver.resolve("ci")
        assertNotNull(profile)
        assertTrue(profile.exitOnError)
    }

    @Test
    fun `built-in pre-push profile includes only fast rules`() {
        val profile = ExecutionProfileResolver.resolve("pre-push")
        assertNotNull(profile)
        assertTrue(profile.includeRules.contains("title-format"))
        assertTrue(profile.includeRules.contains("commit-messages"))
        assertTrue(profile.includeRules.contains("branch-naming"))
        assertTrue(profile.includeRules.contains("draft"))
        assertFalse(profile.aiEnabled)
    }

    @Test
    fun `built-in pre-push profile has timeout`() {
        val profile = ExecutionProfileResolver.resolve("pre-push")
        assertNotNull(profile)
        assertTrue(profile.totalTimeout.seconds > 0)
    }

    @Test
    fun `built-in security-audit profile includes security rules`() {
        val profile = ExecutionProfileResolver.resolve("security-audit")
        assertNotNull(profile)
        assertTrue(profile.includeRules.any { it.contains("security") })
        assertTrue(profile.includeRules.any { it.contains("codeowners") })
        assertTrue(profile.aiEnabled)
        assertTrue(profile.exitOnError)
    }

    @Test
    fun `built-in local profile is verbose and non-blocking`() {
        val profile = ExecutionProfileResolver.resolve("local")
        assertNotNull(profile)
        assertTrue(profile.verbose)
        assertFalse(profile.exitOnError)
        assertTrue(profile.aiEnabled)
    }

    @Test
    fun `custom profile overrides built-in with same name`() {
        val custom = ExecutionProfileConfig(description = "custom-ci", aiEnabled = true)
        val profile = ExecutionProfileResolver.resolve("ci", mapOf("ci" to custom))
        assertNotNull(profile)
        assertEquals("custom-ci", profile.description)
        assertTrue(profile.aiEnabled)
    }

    @Test
    fun `custom profile is returned when no built-in matches`() {
        val custom = ExecutionProfileConfig(description = "my-profile")
        val profile = ExecutionProfileResolver.resolve("my-profile", mapOf("my-profile" to custom))
        assertNotNull(profile)
        assertEquals("my-profile", profile.description)
    }

    @Test
    fun `filterRules with includeRules keeps only matching rules`() {
        val profile = ExecutionProfileConfig(includeRules = listOf("title", "draft"))
        val all = setOf("title-format", "commit-messages", "draft", "branch-naming")
        val result = ExecutionProfileResolver.filterRules(profile, all)
        assertEquals(setOf("title-format", "draft"), result)
    }

    @Test
    fun `filterRules with empty includeRules keeps all rules`() {
        val profile = ExecutionProfileConfig()
        val all = setOf("title-format", "commit-messages", "draft")
        val result = ExecutionProfileResolver.filterRules(profile, all)
        assertEquals(all, result)
    }

    @Test
    fun `filterRules with excludeRules removes matching rules`() {
        val profile = ExecutionProfileConfig(excludeRules = listOf("ai-"))
        val all = setOf("title-format", "ai-review", "ai-security", "draft")
        val result = ExecutionProfileResolver.filterRules(profile, all)
        assertEquals(setOf("title-format", "draft"), result)
    }

    @Test
    fun `filterRules applies include then exclude`() {
        val profile = ExecutionProfileConfig(
            includeRules = listOf("ai-"),
            excludeRules = listOf("ai-security"),
        )
        val all = setOf("title-format", "ai-review", "ai-security", "draft")
        val result = ExecutionProfileResolver.filterRules(profile, all)
        assertEquals(setOf("ai-review"), result)
    }

    @Test
    fun `filterRules returns empty set when includeRules matches nothing`() {
        val profile = ExecutionProfileConfig(includeRules = listOf("nonexistent"))
        val all = setOf("title-format", "commit-messages")
        val result = ExecutionProfileResolver.filterRules(profile, all)
        assertTrue(result.isEmpty())
    }
}
