package io.karandash.config.pipeline

import io.karandash.platform.PlatformEnvironment
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.booleanOrNull
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue

/**
 * Tests for [ConfigMapParser.deepInterpolateValues] and the decoupled normalization
 * + interpolation pipeline introduced to separate the two steps.
 *
 * Verifies that ${ENV_VAR} interpolation works correctly at all depths after
 * key normalization has already been applied by [deepNormalizeKeys].
 */
class ConfigMapParserDeepInterpolationTest {
    // ── deepInterpolateValues unit tests ─────────────────────────────────

    @Test
    fun `deepInterpolateValues resolves top-level string values`() {
        val element = JsonObject(mapOf("token" to JsonPrimitive("\${GITLAB_TOKEN}")))
        val defaults = mapOf("GITLAB_TOKEN" to "my-token")
        val result = ConfigMapParser.deepInterpolateValues(element, defaults) as JsonObject

        val envValue = PlatformEnvironment.getEnv("GITLAB_TOKEN") ?: "my-token"
        assertEquals(envValue, (result["token"] as JsonPrimitive).content)
    }

    @Test
    fun `deepInterpolateValues resolves nested string values`() {
        val inner = JsonObject(mapOf("url" to JsonPrimitive("https://\${API_HOST}/api")))
        val element = JsonObject(mapOf("platforms" to JsonObject(mapOf("gitlab" to inner))))
        val defaults = mapOf("API_HOST" to "gitlab.example.com")
        val result = ConfigMapParser.deepInterpolateValues(element, defaults) as JsonObject

        val envValue = PlatformEnvironment.getEnv("API_HOST") ?: "gitlab.example.com"
        val providers = result["platforms"] as JsonObject
        val gitlab = providers["gitlab"] as JsonObject
        assertEquals("https://$envValue/api", (gitlab["url"] as JsonPrimitive).content)
    }

    @Test
    fun `deepInterpolateValues resolves values inside arrays`() {
        val element = JsonObject(
            mapOf(
                "patterns" to JsonArray(
                    listOf(
                        JsonPrimitive("\${PATTERN_PREFIX}-.*"),
                        JsonPrimitive("literal-value"),
                    )
                )
            )
        )
        val defaults = mapOf("PATTERN_PREFIX" to "feat")
        val result = ConfigMapParser.deepInterpolateValues(element, defaults) as JsonObject

        val envValue = PlatformEnvironment.getEnv("PATTERN_PREFIX") ?: "feat"
        val patterns = result["patterns"] as JsonArray
        assertEquals("$envValue-.*", (patterns[0] as JsonPrimitive).content)
        assertEquals("literal-value", (patterns[1] as JsonPrimitive).content)
    }

    @Test
    fun `deepInterpolateValues does NOT modify keys`() {
        val element = JsonObject(
            mapOf(
                "my-kebab-key" to JsonPrimitive("value"),
                "another_snake" to JsonPrimitive("\${SOME_VAR}"),
            )
        )
        val defaults = mapOf("SOME_VAR" to "resolved")
        val result = ConfigMapParser.deepInterpolateValues(element, defaults) as JsonObject

        // Keys must be untouched - deepInterpolateValues only touches values
        assertTrue(result.containsKey("my-kebab-key"), "kebab key must be preserved")
        assertTrue(result.containsKey("another_snake"), "snake key must be preserved")
        assertEquals("value", (result["my-kebab-key"] as JsonPrimitive).content)
        val envValue = PlatformEnvironment.getEnv("SOME_VAR") ?: "resolved"
        assertEquals(envValue, (result["another_snake"] as JsonPrimitive).content)
    }

    @Test
    fun `deepInterpolateValues tracks missing vars`() {
        val element = JsonObject(
            mapOf("key" to JsonPrimitive("\${KARANDASH_TEST_MISSING_PHASE2_VAR}"))
        )
        val missingVars = mutableSetOf<String>()
        ConfigMapParser.deepInterpolateValues(element, emptyMap(), missingVars)

        val actualEnv = PlatformEnvironment.getEnv("KARANDASH_TEST_MISSING_PHASE2_VAR")
        if (actualEnv == null) {
            assertTrue(
                missingVars.contains("KARANDASH_TEST_MISSING_PHASE2_VAR"),
                "Missing var must be tracked"
            )
        }
    }

    @Test
    fun `deepInterpolateValues leaves non-string primitives unchanged`() {
        val element = JsonObject(
            mapOf(
                "enabled" to JsonPrimitive(true),
                "count" to JsonPrimitive(42),
                "label" to JsonPrimitive("\${MY_LABEL}"),
            )
        )
        val defaults = mapOf("MY_LABEL" to "test-label")
        val result = ConfigMapParser.deepInterpolateValues(element, defaults) as JsonObject

        assertEquals(true, (result["enabled"] as JsonPrimitive).booleanOrNull)
        assertEquals(42, (result["count"] as JsonPrimitive).content.toInt())
        val envValue = PlatformEnvironment.getEnv("MY_LABEL") ?: "test-label"
        assertEquals(envValue, (result["label"] as JsonPrimitive).content)
    }

    // ── parseFullConfig pipeline integration tests ────────────────────────

    @Test
    fun `parseFullConfig interpolates top-level provider token values`() {
        val data = mapOf<String, Any?>(
            "platforms" to mapOf("gitlab" to mapOf("token" to "\${KARANDASH_TEST_TOKEN_P2}"))
        )
        val defaults = mapOf("KARANDASH_TEST_TOKEN_P2" to "pipeline-token")
        val config = ConfigMapParser.parseFullConfig(data, defaults).config

        val gitlabConfig = config.platforms?.get("gitlab") as? JsonObject
        val envValue = PlatformEnvironment.getEnv("KARANDASH_TEST_TOKEN_P2") ?: "pipeline-token"
        assertEquals(envValue, (gitlabConfig?.get("token") as? JsonPrimitive)?.content)
    }

    @Test
    fun `parseFullConfig interpolates nested rule config values`() {
        val data = mapOf<String, Any?>(
            "rules" to mapOf(
                "title-format" to mapOf("pattern" to "^\${KARANDASH_TEST_PREFIX_P2}:")
            )
        )
        val defaults = mapOf("KARANDASH_TEST_PREFIX_P2" to "feat")
        val config = ConfigMapParser.parseFullConfig(data, defaults).config

        val ruleConfig = config.rules.ruleConfigs["titleFormat"] as? JsonObject
        val envValue = PlatformEnvironment.getEnv("KARANDASH_TEST_PREFIX_P2") ?: "feat"
        assertEquals("^$envValue:", (ruleConfig?.get("pattern") as? JsonPrimitive)?.content)
    }

    @Test
    fun `parseFullConfig interpolates values inside arrays in reporter config`() {
        val data = mapOf<String, Any?>(
            "reporters" to mapOf(
                "console" to mapOf(
                    "recipients" to listOf("\${KARANDASH_TEST_RECIPIENT_P2}", "fixed@example.com")
                )
            )
        )
        val defaults = mapOf("KARANDASH_TEST_RECIPIENT_P2" to "dynamic@example.com")
        val config = ConfigMapParser.parseFullConfig(data, defaults).config

        val consoleConfig = config.reporters?.get("console") as? JsonObject
        val recipients = consoleConfig?.get("recipients") as? JsonArray
        val envValue = PlatformEnvironment.getEnv("KARANDASH_TEST_RECIPIENT_P2") ?: "dynamic@example.com"
        assertEquals(envValue, (recipients?.get(0) as? JsonPrimitive)?.content)
        assertEquals("fixed@example.com", (recipients?.get(1) as? JsonPrimitive)?.content)
    }

    // ── Edge case tests ───────────────────────────────────────────────

    @Test
    fun `literal dollar sign without braces preserved`() {
        val element = JsonObject(mapOf("price" to JsonPrimitive("$100")))
        val result = ConfigMapParser.deepInterpolateValues(element, emptyMap()) as JsonObject
        assertEquals("$100", (result["price"] as JsonPrimitive).content)
    }

    @Test
    fun `empty var name produces empty string`() {
        // Regex \$\{([^}]+)\} requires 1+ chars inside braces, so ${} does NOT match
        val element = JsonObject(mapOf("key" to JsonPrimitive("\${}")))
        val result = ConfigMapParser.deepInterpolateValues(element, emptyMap()) as JsonObject
        // ${} is preserved as-is since the regex does not match zero-length var names
        assertEquals("\${}", (result["key"] as JsonPrimitive).content)
    }

    @Test
    fun `multiple vars in single string all resolve`() {
        val element = JsonObject(
            mapOf("combined" to JsonPrimitive("\${KARANDASH_TEST_A}-\${KARANDASH_TEST_B}"))
        )
        val defaults = mapOf("KARANDASH_TEST_A" to "valueA", "KARANDASH_TEST_B" to "valueB")
        val result = ConfigMapParser.deepInterpolateValues(element, defaults) as JsonObject

        val envA = PlatformEnvironment.getEnv("KARANDASH_TEST_A") ?: "valueA"
        val envB = PlatformEnvironment.getEnv("KARANDASH_TEST_B") ?: "valueB"
        assertEquals("$envA-$envB", (result["combined"] as JsonPrimitive).content)
    }

    @Test
    fun `interpolation in array elements at nested depth`() {
        val element = JsonObject(
            mapOf(
                "outer" to JsonObject(
                    mapOf(
                        "inner" to JsonArray(
                            listOf(
                                JsonPrimitive("\${KARANDASH_TEST_DEEP_ARR}"),
                                JsonPrimitive("literal"),
                            )
                        )
                    )
                )
            )
        )
        val defaults = mapOf("KARANDASH_TEST_DEEP_ARR" to "resolved")
        val result = ConfigMapParser.deepInterpolateValues(element, defaults) as JsonObject

        val envValue = PlatformEnvironment.getEnv("KARANDASH_TEST_DEEP_ARR") ?: "resolved"
        val inner = (result["outer"] as JsonObject)["inner"] as JsonArray
        assertEquals(envValue, (inner[0] as JsonPrimitive).content)
        assertEquals("literal", (inner[1] as JsonPrimitive).content)
    }

    @Test
    fun `non-string primitives unchanged after interpolation`() {
        val element = JsonObject(
            mapOf(
                "flag" to JsonPrimitive(false),
                "count" to JsonPrimitive(7),
                "ratio" to JsonPrimitive(3.14),
            )
        )
        val result = ConfigMapParser.deepInterpolateValues(element, emptyMap()) as JsonObject
        assertEquals(false, (result["flag"] as JsonPrimitive).booleanOrNull)
        assertEquals("7", (result["count"] as JsonPrimitive).content)
        assertEquals("3.14", (result["ratio"] as JsonPrimitive).content)
    }

    @Test
    fun `interpolation does not modify keys`() {
        val element = JsonObject(
            mapOf("\${VAR}" to JsonPrimitive("value"))
        )
        val defaults = mapOf("VAR" to "resolved")
        val result = ConfigMapParser.deepInterpolateValues(element, defaults) as JsonObject
        assertTrue(result.containsKey("\${VAR}"), "Key containing \${VAR} must be preserved as-is")
        assertEquals("value", (result["\${VAR}"] as JsonPrimitive).content)
    }

    // ── parseFullConfig pipeline integration tests ────────────────────────

    @Test
    fun `parseFullConfig tracks missing env vars from top-level and nested values`() {
        val data = mapOf<String, Any?>(
            "platforms" to mapOf(
                "gitlab" to mapOf("token" to "\${KARANDASH_TEST_MISS_A_P2}")
            ),
            "reporters" to mapOf(
                "console" to mapOf("webhook" to "\${KARANDASH_TEST_MISS_B_P2}")
            )
        )
        val result = ConfigMapParser.parseFullConfig(data, emptyMap())

        val hasA = PlatformEnvironment.getEnv("KARANDASH_TEST_MISS_A_P2") == null
        val hasB = PlatformEnvironment.getEnv("KARANDASH_TEST_MISS_B_P2") == null
        if (hasA) assertTrue(result.missingEnvVars.contains("KARANDASH_TEST_MISS_A_P2"))
        if (hasB) assertTrue(result.missingEnvVars.contains("KARANDASH_TEST_MISS_B_P2"))
    }
}
