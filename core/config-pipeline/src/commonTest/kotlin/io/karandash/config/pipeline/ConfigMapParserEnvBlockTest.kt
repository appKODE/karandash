package io.karandash.config.pipeline

import io.karandash.api.errors.ConfigException
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertNull
import kotlin.test.assertTrue

/**
 * Tests for [ConfigMapParser.parseEnvBlock] and interpolation with defaults.
 */
class ConfigMapParserEnvBlockTest {
    // --- parseEnvBlock: list form ---

    @Test
    fun `parseEnvBlock list form produces names with null defaults`() {
        val data = mapOf<String, Any?>("env" to listOf("TASK_ID", "JIRA_KEY"))
        val decl = ConfigMapParser.parseEnvBlock(data)

        assertEquals(setOf("TASK_ID", "JIRA_KEY"), decl.names)
        assertNull(decl.vars["TASK_ID"])
        assertNull(decl.vars["JIRA_KEY"])
    }

    @Test
    fun `parseEnvBlock list form ignores non-string entries`() {
        val data = mapOf<String, Any?>("env" to listOf("TASK_ID", 42, true))
        val decl = ConfigMapParser.parseEnvBlock(data)

        assertEquals(setOf("TASK_ID"), decl.names)
    }

    // --- parseEnvBlock: map form ---

    @Test
    fun `parseEnvBlock map form with defaults`() {
        val data = mapOf<String, Any?>(
            "env" to mapOf(
                "TASK_ID" to null,
                "JIRA_KEY" to "PROJ",
                "VERSION" to "1.0.0"
            )
        )
        val decl = ConfigMapParser.parseEnvBlock(data)

        assertEquals(setOf("TASK_ID", "JIRA_KEY", "VERSION"), decl.names)
        assertNull(decl.vars["TASK_ID"])
        assertEquals("PROJ", decl.vars["JIRA_KEY"])
        assertEquals("1.0.0", decl.vars["VERSION"])
    }

    @Test
    fun `parseEnvBlock map form converts non-string defaults to string`() {
        val data = mapOf<String, Any?>(
            "env" to mapOf("PORT" to 8080)
        )
        val decl = ConfigMapParser.parseEnvBlock(data)

        assertEquals("8080", decl.vars["PORT"])
    }

    // --- parseEnvBlock: missing / null ---

    @Test
    fun `parseEnvBlock returns EMPTY when no env block`() {
        val data = mapOf<String, Any?>("rules" to mapOf("title-format" to mapOf("pattern" to "^feat:")))
        val decl = ConfigMapParser.parseEnvBlock(data)

        assertEquals(EnvDeclaration.EMPTY, decl)
    }

    @Test
    fun `parseEnvBlock returns EMPTY when env is null`() {
        val data = mapOf<String, Any?>("env" to null)
        val decl = ConfigMapParser.parseEnvBlock(data)

        assertEquals(EnvDeclaration.EMPTY, decl)
    }

    // --- parseEnvBlock: invalid type ---

    @Test
    fun `parseEnvBlock throws E0008 for invalid type`() {
        val data = mapOf<String, Any?>("env" to "not-valid")
        val ex = assertFailsWith<ConfigException> {
            ConfigMapParser.parseEnvBlock(data)
        }
        assertTrue(ex.message!!.contains("E0008"))
    }

    @Test
    fun `parseEnvBlock throws E0008 for integer type`() {
        val data = mapOf<String, Any?>("env" to 42)
        val ex = assertFailsWith<ConfigException> {
            ConfigMapParser.parseEnvBlock(data)
        }
        assertTrue(ex.message!!.contains("E0008"))
    }

    // --- parseBootstrapConfig with env ---

    @Test
    fun `SECURITY - parseBootstrapConfig rejects restricted vars`() {
        val data = mapOf<String, Any?>(
            "env" to listOf("HOME", "TASK_ID")
        )
        val ex = assertFailsWith<ConfigException> {
            ConfigMapParser.parseBootstrapConfig(data)
        }
        assertTrue(ex.message!!.contains("E0021"))
        assertTrue(ex.message!!.contains("HOME"))
    }

    // --- interpolate with defaults ---

    @Test
    fun `interpolate uses default when env var not set`() {
        val defaults = mapOf("MY_VAR" to "default-value")
        val result = ConfigMapParser.interpolate("\${MY_VAR}", defaults = defaults)

        // If MY_VAR is actually set in the environment, it takes precedence;
        // otherwise, the default is used
        val envValue = io.karandash.platform.PlatformEnvironment
            .getEnv("MY_VAR")
        if (envValue != null) {
            assertEquals(envValue, result)
        } else {
            assertEquals("default-value", result)
        }
    }

    @Test
    fun `interpolate default is not used when var is in denylist`() {
        val denylist = setOf("MY_VAR")
        val defaults = mapOf("MY_VAR" to "default-value")
        val result = ConfigMapParser.interpolate("\${MY_VAR}", defaults, denylist)

        assertEquals("\${MY_VAR}", result, "Default should not apply when var is in denylist")
    }

    @Test
    fun `interpolate with multiple vars some with defaults`() {
        val defaults = mapOf("A" to "default-a", "B" to "default-b")
        val result = ConfigMapParser.interpolate("\${A}:\${B}", defaults = defaults)

        val envA = io.karandash.platform.PlatformEnvironment
            .getEnv("A")
        val envB = io.karandash.platform.PlatformEnvironment
            .getEnv("B")

        val expectedA = envA ?: "default-a"
        val expectedB = envB ?: "default-b"
        assertEquals("$expectedA:$expectedB", result)
    }

    @Test
    fun `interpolate resolves to empty for var without default when unset`() {
        val defaults = mapOf("A" to "default-a")
        val missingVars = mutableSetOf<String>()
        // B has no default and is (almost certainly) not set
        val result = ConfigMapParser.interpolate("\${A}:\${B}", defaults = defaults, missingVars = missingVars)

        val envA = io.karandash.platform.PlatformEnvironment
            .getEnv("A")
        val envB = io.karandash.platform.PlatformEnvironment
            .getEnv("B")
        val expectedA = envA ?: "default-a"
        val expectedB = envB ?: ""
        assertEquals("$expectedA:$expectedB", result)
        if (envB == null) {
            assertTrue(missingVars.contains("B"), "B should be tracked as missing")
        }
    }

    // --- parseFullConfig with env defaults ---

    @Test
    fun `parseFullConfig applies env defaults to provider config values`() {
        val data = mapOf<String, Any?>(
            "env" to mapOf("MY_TOKEN" to "fallback-token"),
            "platforms" to mapOf(
                "gitlab" to mapOf("token" to "\${MY_TOKEN}")
            )
        )
        val defaults = mapOf("MY_TOKEN" to "fallback-token")
        val config = ConfigMapParser.parseFullConfig(data, defaults).config

        val gitlabConfig = config.platforms?.get("gitlab")
        val envValue = io.karandash.platform.PlatformEnvironment
            .getEnv("MY_TOKEN")
        val expected = envValue ?: "fallback-token"
        assertEquals(
            expected,
            gitlabConfig
                ?.jsonObject
                ?.get("token")
                ?.jsonPrimitive
                ?.content
        )
    }

    @Test
    fun `parseFullConfig applies env defaults to rule config values`() {
        val data = mapOf<String, Any?>(
            "rules" to mapOf(
                "title-format" to mapOf("pattern" to "^\${TASK_PREFIX}:")
            )
        )
        val defaults = mapOf("TASK_PREFIX" to "feat")
        val config = ConfigMapParser.parseFullConfig(data, defaults).config

        val ruleConfig = config.rules.ruleConfigs["titleFormat"]
        val envValue = io.karandash.platform.PlatformEnvironment
            .getEnv("TASK_PREFIX")
        val expected = "^${envValue ?: "feat"}:"
        assertEquals(
            expected,
            ruleConfig
                ?.jsonObject
                ?.get("pattern")
                ?.jsonPrimitive
                ?.content
        )
    }

    // --- env block through the parse path ---

    @Test
    fun `env block default fills an unset variable in a rule value and without the block it is missing`() {
        val rules = mapOf("title-format" to mapOf("pattern" to "^\${KARANDASH_TEST_UNSET_ENV_DEFAULT}:"))
        val declared = mapOf<String, Any?>("env" to mapOf("KARANDASH_TEST_UNSET_ENV_DEFAULT" to "feat"), "rules" to rules)
        val undeclared = mapOf<String, Any?>("rules" to rules)

        val withBlock = ConfigMapParser.parseFullConfig(declared, ConfigMapParser.parseEnvBlock(declared).defaults)
        val withoutBlock = ConfigMapParser.parseFullConfig(undeclared, ConfigMapParser.parseEnvBlock(undeclared).defaults)

        assertEquals(
            "^feat:",
            withBlock.config.rules.ruleConfigs["titleFormat"]
                ?.jsonObject
                ?.get("pattern")
                ?.jsonPrimitive
                ?.content
        )
        assertEquals(
            "^:",
            withoutBlock.config.rules.ruleConfigs["titleFormat"]
                ?.jsonObject
                ?.get("pattern")
                ?.jsonPrimitive
                ?.content
        )
    }

    @Test
    fun `env block accepts a name outside the denylist where the same block with a denylisted name is rejected`() {
        val accepted = ConfigMapParser.parseBootstrapConfig(mapOf<String, Any?>("env" to listOf("TASK_ID")))
        assertEquals(emptyList(), accepted.extends)
        assertFailsWith<ConfigException> {
            ConfigMapParser.parseBootstrapConfig(mapOf<String, Any?>("env" to mapOf("HOME" to "default-value")))
        }
    }

    // --- Empty list form ---

    @Test
    fun `parseEnvBlock empty list returns EMPTY`() {
        val data = mapOf<String, Any?>("env" to emptyList<Any>())
        val decl = ConfigMapParser.parseEnvBlock(data)

        assertEquals(EnvDeclaration.EMPTY.names, decl.names)
    }

    @Test
    fun `parseEnvBlock empty map returns EMPTY`() {
        val data = mapOf<String, Any?>("env" to emptyMap<String, Any?>())
        val decl = ConfigMapParser.parseEnvBlock(data)

        assertEquals(EnvDeclaration.EMPTY.names, decl.names)
    }
}
