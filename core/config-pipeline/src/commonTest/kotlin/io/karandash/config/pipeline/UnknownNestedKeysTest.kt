package io.karandash.config.pipeline

import io.karandash.api.InternalKarandashApi
import io.karandash.api.ModuleRegistryImpl
import io.karandash.api.PluginContext
import io.karandash.config.RulesConfig
import io.karandash.models.MergeRequest
import io.karandash.models.finding.Finding
import io.karandash.sdk.typed.TypedRuleModule
import kotlinx.coroutines.test.runTest
import kotlinx.serialization.ExperimentalSerializationApi
import kotlinx.serialization.Serializable
import kotlinx.serialization.json.JsonElement
import kotlinx.serialization.json.JsonNames
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.put
import kotlinx.serialization.json.putJsonObject
import kotlin.test.Test
import kotlin.test.assertEquals

/** Unknown keys inside built-in sections and typed rule configs are warned about, never fatal. */
@OptIn(InternalKarandashApi::class, ExperimentalSerializationApi::class)
class UnknownNestedKeysTest {
    @Serializable
    data class LengthConfig(
        @JsonNames("max") val maxLength: Int = 72,
        val nested: Inner = Inner(),
    )

    @Serializable
    data class Inner(
        val enabled: Boolean = true,
    )

    private class LengthRule : TypedRuleModule<LengthConfig> {
        override val configSerializer = LengthConfig.serializer()
        override val id = "length-rule"
        override val name = "Length Rule"
        override val version = "1.0"

        override suspend fun contributedRules() = setOf("title-length")

        override suspend fun initialize(context: PluginContext) = true

        override suspend fun validateTyped(
            ruleId: String,
            mr: MergeRequest,
            config: LengthConfig,
        ) = emptyList<Finding>()
    }

    private class PlatformOwner : TypedRuleModule<LengthConfig> {
        override val configSerializer = LengthConfig.serializer()
        override val id = "platform-owner"
        override val name = "Platform Owner"
        override val version = "1.0"
        override val configKey = "platform"

        override suspend fun contributedRules() = emptySet<String>()

        override suspend fun initialize(context: PluginContext) = true

        override suspend fun validateTyped(
            ruleId: String,
            mr: MergeRequest,
            config: LengthConfig,
        ) = emptyList<Finding>()
    }

    private fun rules(config: JsonElement) = RulesConfig(ruleConfigs = mapOf("titleLength" to config))

    @Test
    fun `unknown keys in built-in sections are reported with their path`() =
        runTest {
            val root = buildJsonObject {
                putJsonObject("cache") {
                    put("enabled", true)
                    put("max-entires", 5)
                    putJsonObject("memory") { putJsonObject("overrides") { put("any-name", 3) } }
                }
                putJsonObject("timeouts") { put("bogus", 1) }
            }

            val warnings = UnknownNestedKeys.warnings(root, RulesConfig(), ModuleRegistryImpl())

            assertEquals(2, warnings.size, warnings.toString())
            assertEquals(true, warnings[0].contains("'cache.maxEntires'"), warnings[0])
            assertEquals(true, warnings[1].contains("'timeouts.bogus'"), warnings[1])
        }

    @Test
    fun `typed rule config keys are checked against the rule's config class`() =
        runTest {
            val registry = ModuleRegistryImpl().apply { register(LengthRule()) }
            val config = buildJsonObject {
                put("enabled", true)
                put("max", 50)
                put("maxLenght", 60)
                putJsonObject("nested") { put("enabled", false) }
            }

            val warnings = UnknownNestedKeys.warnings(JsonObject(emptyMap()), rules(config), registry)

            assertEquals(1, warnings.size, warnings.toString())
            assertEquals(true, warnings.single().contains("'rules.titleLength.maxLenght'"), warnings.single())
        }

    @Test
    fun `rules without a typed module and known keys report nothing`() =
        runTest {
            val config = JsonObject(mapOf("anything" to JsonPrimitive(1)))
            val root = buildJsonObject { putJsonObject("limits") { putJsonObject("glob") { put("maxExpansion", 10) } } }

            assertEquals(emptyList(), UnknownNestedKeys.warnings(root, rules(config), ModuleRegistryImpl()))
        }

    @Test
    fun `kebab-case keys under limits patterns are known settings`() =
        runTest {
            val root = buildJsonObject { putJsonObject("limits") { putJsonObject("patterns") { put("max-expansion-depth", 20) } } }

            assertEquals(emptyList(), UnknownNestedKeys.warnings(root, RulesConfig(), ModuleRegistryImpl()))
        }

    @Test
    fun `a module-owned top-level block with keys its module does not read suggests the built-in key`() =
        runTest {
            val registry = ModuleRegistryImpl().apply { register(PlatformOwner()) }
            val typo = buildJsonObject { putJsonObject("gitlab") { put("url", "https://gitlab.example.com") } }

            val warnings = UnknownNestedKeys.warnings(JsonObject(emptyMap()), RulesConfig(), registry, mapOf("platform" to typo))

            assertEquals(1, warnings.size, warnings.toString())
            assertEquals(true, warnings.single().contains("'platform.gitlab'"), warnings.single())
            assertEquals(true, warnings.single().contains("Did you mean top-level 'platforms'?"), warnings.single())
            val valid = buildJsonObject { put("max", 5) }
            assertEquals(
                emptyList(),
                UnknownNestedKeys.warnings(JsonObject(emptyMap()), RulesConfig(), registry, mapOf("platform" to valid))
            )
        }
}
