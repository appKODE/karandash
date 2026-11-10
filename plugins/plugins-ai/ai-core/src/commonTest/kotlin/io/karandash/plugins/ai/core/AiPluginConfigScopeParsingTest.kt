package io.karandash.plugins.ai.core

import io.karandash.api.support.mapToJsonElement
import io.karandash.sdk.content.ContentSource
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertIs
import kotlin.test.assertNull
import kotlin.test.assertTrue

class AiPluginConfigScopeParsingTest {
    @Test
    fun `scopes defaults to empty list`() {
        val config = AiPluginConfig.fromPluginConfig(mapToJsonElement(emptyMap()))
        assertTrue(config.scopes.isEmpty())
    }

    @Test
    fun `parses single scope with all fields`() {
        val config = AiPluginConfig.fromPluginConfig(
            mapToJsonElement(
                mapOf(
                    "scopes" to mapOf(
                        "android" to mapOf(
                            "paths" to listOf("android/**", "app/src/**/*.kt"),
                            "instructions" to "Check lifecycle issues.",
                            "bestPractices" to "Use ViewModel.",
                        )
                    )
                )
            )
        )
        assertEquals(1, config.scopes.size)
        val scope = config.scopes[0]
        assertEquals("android", scope.name)
        assertEquals(listOf("android/**", "app/src/**/*.kt"), scope.paths)
        assertEquals("Check lifecycle issues.", assertIs<ContentSource.Inline>(scope.instructions).value)
        assertEquals("Use ViewModel.", assertIs<ContentSource.Inline>(scope.bestPractices).value)
    }

    @Test
    fun `parses multiple scopes`() {
        val config = AiPluginConfig.fromPluginConfig(
            mapToJsonElement(
                mapOf(
                    "scopes" to mapOf(
                        "android" to mapOf(
                            "paths" to listOf("android/**"),
                            "instructions" to "Android review.",
                        ),
                        "ios" to mapOf(
                            "paths" to listOf("ios/**"),
                            "instructions" to "iOS review.",
                        ),
                    )
                )
            )
        )
        assertEquals(2, config.scopes.size)
    }

    @Test
    fun `scope with missing paths is skipped`() {
        val config = AiPluginConfig.fromPluginConfig(
            mapToJsonElement(
                mapOf(
                    "scopes" to mapOf(
                        "broken" to mapOf(
                            "instructions" to "No paths here.",
                        )
                    )
                )
            )
        )
        assertTrue(config.scopes.isEmpty())
    }

    @Test
    fun `scope with empty paths list is skipped`() {
        val config = AiPluginConfig.fromPluginConfig(
            mapToJsonElement(
                mapOf(
                    "scopes" to mapOf(
                        "broken" to mapOf(
                            "paths" to emptyList<String>(),
                            "instructions" to "Empty paths.",
                        )
                    )
                )
            )
        )
        assertTrue(config.scopes.isEmpty())
    }

    @Test
    fun `scope with only paths and no instructions`() {
        val config = AiPluginConfig.fromPluginConfig(
            mapToJsonElement(
                mapOf(
                    "scopes" to mapOf(
                        "minimal" to mapOf(
                            "paths" to listOf("src/**"),
                        )
                    )
                )
            )
        )
        assertEquals(1, config.scopes.size)
        val scope = config.scopes[0]
        assertEquals("minimal", scope.name)
        assertEquals(listOf("src/**"), scope.paths)
        assertNull(scope.instructions)
        assertNull(scope.bestPractices)
    }

    @Test
    fun `non-map scope entry is skipped`() {
        val config = AiPluginConfig.fromPluginConfig(
            mapToJsonElement(
                mapOf(
                    "scopes" to mapOf(
                        "bad" to "not a map",
                    )
                )
            )
        )
        assertTrue(config.scopes.isEmpty())
    }

    @Test
    fun `scopes with non-map value at top level defaults to empty`() {
        val config = AiPluginConfig.fromPluginConfig(
            mapToJsonElement(mapOf("scopes" to "invalid"))
        )
        assertTrue(config.scopes.isEmpty())
    }

    @Test
    fun `scope instructions parses inline string as ContentSource Inline`() {
        val config = AiPluginConfig.fromPluginConfig(
            mapToJsonElement(
                mapOf(
                    "scopes" to mapOf(
                        "android" to mapOf(
                            "paths" to listOf("android/**"),
                            "instructions" to "Check lifecycle issues.",
                        )
                    )
                )
            )
        )
        assertEquals(1, config.scopes.size)
        val source = assertIs<ContentSource.Inline>(config.scopes[0].instructions)
        assertEquals("Check lifecycle issues.", source.value)
    }

    @Test
    fun `scope instructions parses file object as ContentSource LocalFile`() {
        val config = AiPluginConfig.fromPluginConfig(
            mapToJsonElement(
                mapOf(
                    "scopes" to mapOf(
                        "android" to mapOf(
                            "paths" to listOf("android/**"),
                            "instructions" to mapOf("file" to ".karandash/scopes/android-instructions.md"),
                        )
                    )
                )
            )
        )
        assertEquals(1, config.scopes.size)
        val source = assertIs<ContentSource.LocalFile>(config.scopes[0].instructions)
        assertEquals(".karandash/scopes/android-instructions.md", source.path)
    }

    @Test
    fun `scope bestPractices parses inline string as ContentSource Inline`() {
        val config = AiPluginConfig.fromPluginConfig(
            mapToJsonElement(
                mapOf(
                    "scopes" to mapOf(
                        "android" to mapOf(
                            "paths" to listOf("android/**"),
                            "bestPractices" to "Use ViewModel.",
                        )
                    )
                )
            )
        )
        assertEquals(1, config.scopes.size)
        val source = assertIs<ContentSource.Inline>(config.scopes[0].bestPractices)
        assertEquals("Use ViewModel.", source.value)
    }

    @Test
    fun `scope bestPractices parses file object as ContentSource LocalFile`() {
        val config = AiPluginConfig.fromPluginConfig(
            mapToJsonElement(
                mapOf(
                    "scopes" to mapOf(
                        "android" to mapOf(
                            "paths" to listOf("android/**"),
                            "bestPractices" to mapOf("file" to ".karandash/scopes/android-best-practices.md"),
                        )
                    )
                )
            )
        )
        assertEquals(1, config.scopes.size)
        val source = assertIs<ContentSource.LocalFile>(config.scopes[0].bestPractices)
        assertEquals(".karandash/scopes/android-best-practices.md", source.path)
    }

    @Test
    fun `scope with no instructions is null`() {
        val config = AiPluginConfig.fromPluginConfig(
            mapToJsonElement(
                mapOf(
                    "scopes" to mapOf(
                        "android" to mapOf(
                            "paths" to listOf("android/**"),
                        )
                    )
                )
            )
        )
        assertEquals(1, config.scopes.size)
        assertNull(config.scopes[0].instructions)
        assertNull(config.scopes[0].bestPractices)
    }
}
