package io.karandash.cli.pipeline

import io.karandash.config.Config
import kotlinx.serialization.json.JsonElement
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlin.test.Test
import kotlin.test.assertEquals

/**
 * Unit tests for the module config resolution logic.
 *
 * With the unified `platforms:` config block, all platform service modules
 * (provider, mutator, comment-poster) share the same config entry.
 */
class ResolveModuleConfigTest {
    /**
     * Resolves module config from the unified `platforms:` block.
     */
    private fun resolveModuleConfig(
        config: Config,
        moduleConfigKey: String,
    ): JsonElement =
        config.platforms?.get(moduleConfigKey)
            ?: JsonObject(emptyMap())

    private val gitlabPlatformConfig = JsonObject(
        mapOf(
            "url" to JsonPrimitive("https://git.example.com"),
            "token" to JsonPrimitive("glpat-test"),
        )
    )

    @Test
    fun `resolves platform config from platforms block`() {
        val config = Config(
            platforms = mapOf("gitlab" to gitlabPlatformConfig),
        )

        val result = resolveModuleConfig(config, moduleConfigKey = "gitlab")

        assertEquals(gitlabPlatformConfig, result)
    }

    @Test
    fun `returns empty JsonObject when platforms is null`() {
        val config = Config(platforms = null)

        val result = resolveModuleConfig(config, moduleConfigKey = "gitlab")

        assertEquals(JsonObject(emptyMap()), result)
    }

    @Test
    fun `returns empty JsonObject when platform key not in map`() {
        val config = Config(
            platforms = mapOf("gitlab" to gitlabPlatformConfig),
        )

        val result = resolveModuleConfig(config, moduleConfigKey = "github")

        assertEquals(JsonObject(emptyMap()), result)
    }

    @Test
    fun `resolves different platform configs independently`() {
        val azurePlatformConfig = JsonObject(
            mapOf("org" to JsonPrimitive("myorg"), "token" to JsonPrimitive("pat-test"))
        )
        val config = Config(
            platforms = mapOf(
                "gitlab" to gitlabPlatformConfig,
                "azureDevOps" to azurePlatformConfig,
            ),
        )

        assertEquals(gitlabPlatformConfig, resolveModuleConfig(config, "gitlab"))
        assertEquals(azurePlatformConfig, resolveModuleConfig(config, "azureDevOps"))
    }
}
